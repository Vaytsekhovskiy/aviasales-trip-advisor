from __future__ import annotations

import json

from .config import OUTPUT_DIR


def add_derived_mse(value):
    """Backfill old reports without fitting: their RMSE was computed as sqrt(MSE)."""
    if isinstance(value, dict):
        for child in value.values(): add_derived_mse(child)
        if "rmse" in value and "mse" not in value:
            value["mse"] = float(value["rmse"]) ** 2
    elif isinstance(value, list):
        for child in value: add_derived_mse(child)
    return value


def _metric_line(metrics: dict) -> str:
    return (f"MAE={metrics['mae']:.2f}, MSE={metrics['mse']:.2f} min^2, RMSE={metrics['rmse']:.2f}, "
            f"MedianAE={metrics['medianAbsoluteError']:.2f}")


def render(evaluation: dict, out_of_time: dict) -> str:
    audit, split = evaluation["datasetAudit"], evaluation["split"]
    internal, heavy = evaluation["internalTest"], evaluation["heavyTailTest"]
    lines = [
        "Departure delay model v2 training report",
        "",
        "DATASET AUDIT",
        f"Total documents: {audit['totalDocumentCount']}",
        f"Unique flightId: {audit['uniqueFlightIdCount']}",
        f"Duplicate flightId documents: {audit['duplicateFlightIdDocumentCount']}",
        f"Scheduled departure range: {audit['minScheduledDepartureUtc']} .. {audit['maxScheduledDepartureUtc']}",
        f"Documents at/after 2026-11-14T00:00:00Z: {audit['documentsAtOrAfterCutoff']}",
        f"Rows after ML filtering: {evaluation['eligibleData']['afterMlFilteringCount']}",
        "",
        "DAILY DOCUMENT COUNTS (UTC)",
    ]
    lines.extend(f"{day}: {count}" for day, count in audit["dailyCountsUtc"].items())
    lines.extend(["", "CHRONOLOGICAL SPLIT"])
    for name in ("train", "validation", "test"):
        part = split[name]
        lines.append(f"{name.upper()}: {part['from']} .. {part['to']}, N={part['count']}")
    lines.extend([
        "",
        "INTERNAL TEST",
        f"Baseline: {_metric_line(internal['baseline'])}",
        f"delay-v2: {_metric_line(internal['model'])}",
        f"Improvement vs baseline MAE: {internal['improvementVsBaselinePct']:.2f}%",
        f"Evaluation best iteration: {evaluation['evaluationBestIteration']}",
        "",
        "HEAVY-TAIL TEST BREAKDOWN",
    ])
    for name in ("targetLe60", "targetLe180", "targetLe360", "targetGt360"):
        item = heavy[name]
        lines.append(f"{name}: N={item['count']}, baseline MAE/MSE/RMSE="
                     f"{item['baseline']['mae']:.2f}/{item['baseline']['mse']:.2f}/{item['baseline']['rmse']:.2f}, "
                     f"v2 MAE/MSE/RMSE={item['model']['mae']:.2f}/{item['model']['mse']:.2f}/"
                     f"{item['model']['rmse']:.2f}, improvement={item['improvementVsBaselinePct']:.2f}%")
    lines.extend(["", "EXTERNAL OUT-OF-TIME CHECK: 2026-11-14"])
    for row in out_of_time["flights"]:
        lines.append(f"{row['flight']}: v1={row['v1Prediction']:.2f}, v2={row['v2Prediction']:.2f}, "
                     f"actual={row['observedActual']:.2f}, v1 error={row['v1AbsoluteError']:.2f}, "
                     f"v2 error={row['v2AbsoluteError']:.2f}")
    lines.extend([
        f"N=3 v1: {_metric_line(out_of_time['metrics']['v1'])}",
        f"N=3 v2: {_metric_line(out_of_time['metrics']['v2'])}",
        "N=3 is too small for a statistically strong conclusion; this is a freshness sanity check only.",
        "",
        "HISTORICAL CONTEXT COMPARISON",
    ])
    for row in out_of_time["flights"]:
        lines.append(row["flight"])
        for version in ("v1", "v2"):
            context = row[f"{version}HistoricalContext"]
            lines.append(
                f"  {version}: profile N/median={context['historicalProfileSampleSize']}/"
                f"{context['historicalProfileMedianDelay']:.2f}; service N/median="
                f"{context['historicalServiceSampleSize']}/{context['historicalServiceMedianDelay']:.2f}; "
                f"route N/median={context['historicalRouteSampleSize']}/"
                f"{context['historicalRouteMedianDelay']:.2f}; airline N/median="
                f"{context['historicalAirlineSampleSize']}/{context['historicalAirlineMedianDelay']:.2f}"
            )
    lines.extend([
        "",
        "DEPLOYMENT",
        "Default model version: delay-v2",
        "Artifacts: models/delay_model_v1.cbm, models/delay_model_v1_metadata.json,",
        "           models/delay_model_v2.cbm, models/delay_model_v2_metadata.json",
        "",
        "SAFETY / METHODOLOGY",
        "- Temporal cutoff is exclusive: 2026-11-14T00:00:00Z.",
        "- 14 November observations were not used for training or model selection.",
        "- Target remains max(0, departureDelayMin), sourced from revised departure.",
        "- Extreme delays were not removed or capped.",
        "- Historical features use strictly earlier scheduled departures.",
        "- CatBoost parameters and feature methodology were not changed.",
        "- No hyperparameter search was performed.",
        "- MongoDB was read only; price logic was not changed.",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    evaluation_path = OUTPUT_DIR / "delay_v2_evaluation.json"
    evaluation = add_derived_mse(json.loads(evaluation_path.read_text(encoding="utf-8")))
    evaluation_path.write_text(json.dumps(evaluation, ensure_ascii=False, indent=2), encoding="utf-8")
    out_of_time = json.loads((OUTPUT_DIR / "delay_v2_out_of_time.json").read_text(encoding="utf-8"))
    report = render(evaluation, out_of_time)
    (OUTPUT_DIR / "delay_v2_training_report.txt").write_text(report, encoding="utf-8")
    (OUTPUT_DIR / "training_report.txt").write_text(report, encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
