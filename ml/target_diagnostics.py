from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from .config import OUTPUT_DIR
from .data_loader import load_data
from .preprocessing import prepare_dataset

FIELDS = ["flightId", "airlineIata", "flightNumber", "originIata", "destinationIata", "status",
          "scheduledDepartureUtc", "revisedDepartureUtc", "runwayDepartureUtc", "collectedAtUtc",
          "revisedDelayMin", "actualTakeoffDelayMin"]


def _json_value(value):
    if value is None or (not isinstance(value, (list, dict)) and pd.isna(value)):
        return None
    if isinstance(value, (pd.Timestamp, np.datetime64)):
        return pd.Timestamp(value).isoformat()
    if isinstance(value, np.generic):
        return value.item()
    return value


def _records(frame: pd.DataFrame, limit: int | None = None) -> list[dict]:
    if limit is not None:
        frame = frame.head(limit)
    return [{key: _json_value(value) for key, value in row.items()} for row in frame.to_dict("records")]


def _distribution(series: pd.Series) -> dict:
    clean = series.dropna()
    return {"count": len(clean), "min": float(clean.min()), "median": float(clean.median()),
            "mean": float(clean.mean()), "p90": float(clean.quantile(.90)), "p95": float(clean.quantile(.95)),
            "p99": float(clean.quantile(.99)), "max": float(clean.max()),
            "countsAboveMinutes": {str(x): int((clean > x).sum()) for x in (60, 180, 360, 720, 1440)}}


def _extremes(frame: pd.DataFrame, target: str) -> dict:
    values = frame[target].dropna()
    return {label: {"count": int((values > threshold).sum()),
                    "examples": _records(frame[frame[target] > threshold].nlargest(10, target)[FIELDS], 10)}
            for label, threshold in (("over6Hours", 360), ("over12Hours", 720), ("over24Hours", 1440))}


def build_diagnostics(documents: list[dict]) -> tuple[dict, pd.DataFrame]:
    clean, data_stats = prepare_dataset(documents)
    for column in ("revisedDepartureUtc", "runwayDepartureUtc", "collectedAtUtc"):
        clean[column] = pd.to_datetime(clean.get(column), utc=True, errors="coerce")
    clean["revisedDelayMin"] = ((clean.revisedDepartureUtc - clean.scheduledDepartureUtc).dt.total_seconds() / 60).clip(lower=0)
    clean["actualTakeoffDelayMin"] = ((clean.runwayDepartureUtc - clean.scheduledDepartureUtc).dt.total_seconds() / 60).clip(lower=0)
    both = clean.dropna(subset=["revisedDelayMin", "actualTakeoffDelayMin"]).copy()
    both["absoluteDifferenceMin"] = (both.revisedDelayMin - both.actualTakeoffDelayMin).abs()
    top = both.nlargest(50, "absoluteDifferenceMin")
    report = {
        "cleanUniqueFlights": len(clean), "data": data_stats,
        "availability": {"revisedDepartureUtc": int(clean.revisedDepartureUtc.notna().sum()),
                         "runwayDepartureUtc": int(clean.runwayDepartureUtc.notna().sum()), "both": len(both)},
        "revisedDelayMin": _distribution(clean.revisedDelayMin),
        "actualTakeoffDelayMin": _distribution(clean.actualTakeoffDelayMin),
        "bothTargetsDifference": {"mae": float(both.absoluteDifferenceMin.mean()),
                                  "medianAbsoluteDifference": float(both.absoluteDifferenceMin.median())},
        "extremeDelays": {"currentTarget": _extremes(clean, "delayTargetMin"),
                          "actualTakeoffTarget": _extremes(clean, "actualTakeoffDelayMin")},
        "top50Differences": _records(top[FIELDS + ["absoluteDifferenceMin"]]),
    }
    return report, top


def main() -> None:
    documents, _ = load_data()
    report, top = build_diagnostics(documents)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUTPUT_DIR / "target_diagnostics.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT_DIR / "extreme_delay_analysis.json").write_text(json.dumps(report["extremeDelays"], ensure_ascii=False, indent=2), encoding="utf-8")
    top[FIELDS + ["absoluteDifferenceMin"]].to_csv(OUTPUT_DIR / "target_top50_differences.csv", index=False)
    (OUTPUT_DIR / "target_top50_differences.json").write_text(
        json.dumps(report["top50Differences"], ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {key: report[key] for key in ("cleanUniqueFlights", "availability", "revisedDelayMin",
                                             "actualTakeoffDelayMin", "bothTargetsDifference")}
    (OUTPUT_DIR / "target_diagnostics.txt").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
