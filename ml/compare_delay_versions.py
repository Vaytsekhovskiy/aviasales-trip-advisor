from __future__ import annotations

import json
from datetime import datetime

import numpy as np
import pandas as pd
from catboost import CatBoostRegressor

from .config import MODEL_DIR, OUTPUT_DIR
from .data_loader import load_data
from .predictor import FINAL_FEATURES, FutureFlightRequest, build_inference_features
from .historical_features import HISTORICAL_FEATURES
from .evaluate import regression_metrics

V1_HISTORY_CUTOFF = pd.Timestamp("2026-10-24T20:50:00Z")
FLIGHTS = [
    ("DP202", "202", "VKO", "2026-11-14T07:20:00+03:00", 257.0),
    ("DP511", "511", "NAL", "2026-11-14T09:00:00+03:00", 20.0),
    ("DP529", "529", "KGD", "2026-11-14T07:10:00+03:00", 61.0),
]


def _load(version: str):
    model = CatBoostRegressor(); model.load_model(str(MODEL_DIR / f"delay_model_{version}.cbm"))
    metadata = json.loads((MODEL_DIR / f"delay_model_{version}_metadata.json").read_text(encoding="utf-8"))
    if metadata["features"] != FINAL_FEATURES or list(model.feature_names_) != FINAL_FEATURES:
        raise RuntimeError(f"{version} feature schema mismatch")
    return model, metadata


def _predict(model, request, documents):
    features, context = build_inference_features(request, documents)
    historical_context = {name: context[name] for name in HISTORICAL_FEATURES}
    return max(0.0, float(model.predict(features)[0])), historical_context


def main() -> None:
    documents, _ = load_data()
    v1_documents = [row for row in documents if row.get("scheduledDepartureUtc") is not None
                    and pd.Timestamp(row["scheduledDepartureUtc"], tz="UTC") <= V1_HISTORY_CUTOFF]
    v1_model, v1_metadata = _load("v1"); v2_model, v2_metadata = _load("v2")
    rows = []
    for label, number, destination, scheduled, actual in FLIGHTS:
        request = FutureFlightRequest.from_dict({"airlineIata": "DP", "flightNumber": number,
            "originIata": "LED", "destinationIata": destination, "scheduledDeparture": scheduled})
        future_utc = request.schedule_row()["scheduledDepartureUtc"]
        v2_documents = [row for row in documents if row.get("scheduledDepartureUtc") is not None
                        and pd.Timestamp(row["scheduledDepartureUtc"], tz="UTC") < pd.Timestamp(future_utc)]
        v1_prediction, v1_context = _predict(v1_model, request, v1_documents)
        v2_prediction, v2_context = _predict(v2_model, request, v2_documents)
        rows.append({"flight": label, "v1Prediction": v1_prediction, "v2Prediction": v2_prediction,
                     "observedActual": actual, "v1AbsoluteError": abs(actual - v1_prediction),
                     "v2AbsoluteError": abs(actual - v2_prediction),
                     "v1HistoricalContext": v1_context, "v2HistoricalContext": v2_context})
    report = {"warning": "N=3 is only a freshness sanity check, not statistically conclusive.",
              "v1ModelVersion": v1_metadata["modelVersion"], "v2ModelVersion": v2_metadata["modelVersion"],
              "v1HistoryCutoffInclusive": V1_HISTORY_CUTOFF.isoformat(), "flights": rows,
              "metrics": {
                  "v1": regression_metrics([row["observedActual"] for row in rows],
                                           [row["v1Prediction"] for row in rows]),
                  "v2": regression_metrics([row["observedActual"] for row in rows],
                                           [row["v2Prediction"] for row in rows])}}
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUTPUT_DIR / "delay_v2_out_of_time.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT_DIR / "delay_v2_out_of_time.txt").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
