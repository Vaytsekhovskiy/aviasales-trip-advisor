from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from datetime import datetime
from pathlib import Path

from pymongo import MongoClient

from .config import DATABASE, FLIGHTS_COLLECTION, MODEL_DIR, OUTPUT_DIR, mongo_uri
from .predictor import DelayPredictor, FutureFlightRequest


class SnapshotHistoryRepository:
    """One Mongo read for a batch; per-request temporal cutoff remains strict."""
    def __init__(self, maximum_cutoff: datetime, uri: str | None = None):
        with MongoClient(uri or mongo_uri()) as client:
            self.documents = list(client[DATABASE][FLIGHTS_COLLECTION].find(
                {"scheduledDepartureUtc": {"$lt": maximum_cutoff}}))

    def read_before(self, cutoff_utc: datetime) -> list[dict]:
        return [row for row in self.documents if row.get("scheduledDepartureUtc") is not None
                and row["scheduledDepartureUtc"].replace(tzinfo=cutoff_utc.tzinfo) < cutoff_utc]


def run_batch(payloads: list[dict], predictor: DelayPredictor) -> dict:
    predictions, errors = [], []
    for index, payload in enumerate(payloads):
        try:
            result = predictor.predict(FutureFlightRequest.from_dict(payload))
            value = result["prediction"]["predictedDelayMin"]
            if not math.isfinite(value) or value < 0:
                raise ValueError("Prediction must be finite and non-negative")
            flight, context = result["flight"], result["historicalContext"]
            predictions.append({**flight, "predictedDelayMin": value,
                                "modelVersion": result["prediction"]["modelVersion"],
                                "historicalContext": context})
        except Exception as error:
            errors.append({"index": index, "request": payload, "error": str(error)})
    values = [row["predictedDelayMin"] for row in predictions]
    summary = {"count": len(values), "min": min(values) if values else None,
               "median": statistics.median(values) if values else None,
               "mean": statistics.fmean(values) if values else None, "max": max(values) if values else None,
               "countAbove30": sum(value > 30 for value in values),
               "countAbove60": sum(value > 60 for value in values),
               "countAbove180": sum(value > 180 for value in values)}
    return {"modelVersion": "delay-v2", "evaluationDate": "2026-11-14",
            "actualDelaysIncluded": False, "flightCount": len(payloads),
            "successfulCount": len(predictions), "errorCount": len(errors),
            "predictions": predictions, "errors": errors, "predictionSummary": summary}


def _flat(row: dict) -> dict:
    context = row["historicalContext"]
    return {**{key: row[key] for key in ("airlineIata", "flightNumber", "originIata", "destinationIata",
                                          "scheduledDeparture", "profileKey", "predictedDelayMin", "modelVersion")},
            **context}


def save_report(report: dict, stem: Path) -> None:
    stem.parent.mkdir(parents=True, exist_ok=True)
    stem.with_suffix(".json").write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    rows = [_flat(row) for row in report["predictions"]]
    with stem.with_suffix(".csv").open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]) if rows else [])
        if rows: writer.writeheader(); writer.writerows(rows)
    lines = ["External delay-v2 predictions for 2026-11-14", "Actual delays included: false", "",
             "flight | route | scheduled | predictedDelayMin | supportLevel | profileN | serviceN"]
    for row in report["predictions"]:
        context = row["historicalContext"]
        lines.append(f"{row['airlineIata']}{row['flightNumber']} | {row['originIata']}-{row['destinationIata']} | "
                     f"{row['scheduledDeparture'][11:16]} | {row['predictedDelayMin']:.1f} | "
                     f"{context['historySupportLevel']} | {context['profileSampleSize']} | {context['serviceSampleSize']}")
    lines.extend(["", "PREDICTION SUMMARY", json.dumps(report["predictionSummary"], ensure_ascii=False, indent=2)])
    if report["errors"]: lines.extend(["", "ERRORS", json.dumps(report["errors"], ensure_ascii=False, indent=2)])
    stem.with_suffix(".txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Batch delay-v2 prediction")
    parser.add_argument("--json-file", type=Path, required=True)
    parser.add_argument("--output-stem", type=Path, default=OUTPUT_DIR / "external_2026-11-14_predictions")
    args = parser.parse_args(argv)
    payloads = json.loads(args.json_file.read_text(encoding="utf-8"))
    requests = [FutureFlightRequest.from_dict(payload) for payload in payloads]
    maximum = max(request.schedule_row()["scheduledDepartureUtc"] for request in requests)
    repository = SnapshotHistoryRepository(maximum)
    predictor = DelayPredictor(repository, MODEL_DIR / "delay_model_v2.cbm",
                               MODEL_DIR / "delay_model_v2_metadata.json")
    report = run_batch(payloads, predictor); save_report(report, args.output_stem)
    print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))
    return 0 if not report["errors"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
