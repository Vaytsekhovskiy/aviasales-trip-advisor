from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone

import numpy as np
import pandas as pd
from catboost import CatBoostRegressor

from .baseline import HierarchicalMedianBaseline
from .config import MODEL_DIR, OUTPUT_DIR, RANDOM_SEED
from .data_loader import load_data
from .evaluate import regression_metrics
from .features import CATEGORICAL_FEATURES, MODEL_FEATURES, add_features
from .historical_features import HISTORICAL_FEATURES, add_leakage_safe_history
from .preprocessing import deduplicate, prepare_dataset

CUTOFF = pd.Timestamp("2026-11-14T00:00:00Z")
VALIDATION_FROM = pd.Timestamp("2026-11-01T00:00:00Z")
TEST_FROM = pd.Timestamp("2026-11-07T00:00:00Z")
FEATURES = MODEL_FEATURES + HISTORICAL_FEATURES
PARAMETERS = {"iterations": 600, "depth": 6, "learning_rate": .05, "l2_leaf_reg": 5,
              "loss_function": "MAE", "eval_metric": "MAE", "random_seed": RANDOM_SEED,
              "task_type": "CPU", "verbose": False, "allow_writing_files": False}


def _iso_range(frame: pd.DataFrame) -> dict:
    return {"from": frame.scheduledDepartureUtc.min().isoformat(),
            "to": frame.scheduledDepartureUtc.max().isoformat(), "count": len(frame)}


def audit_documents(documents: list[dict]) -> dict:
    timestamps = pd.to_datetime([row.get("scheduledDepartureUtc") for row in documents], utc=True, errors="coerce")
    ids = [str(row["flightId"]) for row in documents if row.get("flightId") is not None and str(row["flightId"]).strip()]
    dated = pd.Series(1, index=timestamps).loc[lambda x: x.index.notna()]
    daily = dated.groupby(dated.index.strftime("%Y-%m-%d")).sum()
    requested_days = pd.date_range("2026-10-24", "2026-11-13", freq="D").strftime("%Y-%m-%d")
    return {"totalDocumentCount": len(documents), "uniqueFlightIdCount": len(set(ids)),
            "duplicateFlightIdDocumentCount": len(ids) - len(set(ids)),
            "minScheduledDepartureUtc": timestamps.min().isoformat(),
            "maxScheduledDepartureUtc": timestamps.max().isoformat(),
            "documentsAtOrAfterCutoff": int((timestamps >= CUTOFF).sum()),
            "dailyCountsUtc": {day: int(daily.get(day, 0)) for day in requested_days}}


def _metrics(actual, baseline_prediction, model_prediction) -> dict:
    baseline = regression_metrics(actual, baseline_prediction)
    model = regression_metrics(actual, model_prediction)
    return {"count": len(actual), "baseline": baseline, "model": model,
            "improvementVsBaselinePct": ((baseline["mae"] - model["mae"]) / baseline["mae"] * 100)
            if baseline["mae"] else None}


def _tail(test: pd.DataFrame) -> dict:
    masks = {"targetLe60": test.delayTargetMin <= 60, "targetLe180": test.delayTargetMin <= 180,
             "targetLe360": test.delayTargetMin <= 360, "targetGt360": test.delayTargetMin > 360}
    return {name: _metrics(test.loc[mask, "delayTargetMin"], test.loc[mask, "baselinePrediction"],
                           test.loc[mask, "prediction"]) for name, mask in masks.items()}


def _fit_evaluation(train, validation):
    model = CatBoostRegressor(**PARAMETERS)
    model.fit(train[FEATURES], train.delayTargetMin, cat_features=CATEGORICAL_FEATURES,
              eval_set=(validation[FEATURES], validation.delayTargetMin), early_stopping_rounds=80, verbose=False)
    return model


def _fit_deployment(data):
    model = CatBoostRegressor(**PARAMETERS)
    model.fit(data[FEATURES], data.delayTargetMin, cat_features=CATEGORICAL_FEATURES, verbose=False)
    return model


def main() -> None:
    documents, _ = load_data()
    audit = audit_documents(documents)
    eligible_documents = [row for row in documents if row.get("scheduledDepartureUtc") is not None
                          and pd.Timestamp(row["scheduledDepartureUtc"], tz="UTC") < CUTOFF]
    data, data_stats = prepare_dataset(eligible_documents)
    data = add_leakage_safe_history(add_features(data))
    train = data[data.scheduledDepartureUtc < VALIDATION_FROM].copy()
    validation = data[(data.scheduledDepartureUtc >= VALIDATION_FROM) & (data.scheduledDepartureUtc < TEST_FROM)].copy()
    test = data[(data.scheduledDepartureUtc >= TEST_FROM) & (data.scheduledDepartureUtc < CUTOFF)].copy()
    if any(frame.empty for frame in (train, validation, test)):
        raise RuntimeError("Fixed v2 chronological split produced an empty partition")
    baseline = HierarchicalMedianBaseline().fit(train)
    evaluation_model = _fit_evaluation(train, validation)
    test = test.copy()
    test["baselinePrediction"] = baseline.predict(test)
    test["prediction"] = np.maximum(0, evaluation_model.predict(test[FEATURES]))
    overall = _metrics(test.delayTargetMin, test.baselinePrediction, test.prediction)
    report = {"modelVersion": "delay-v2", "datasetAudit": audit, "eligibleData": data_stats,
              "temporalCutoffExclusive": CUTOFF.isoformat(),
              "split": {"train": _iso_range(train), "validation": _iso_range(validation), "test": _iso_range(test)},
              "evaluationBestIteration": evaluation_model.get_best_iteration(), "internalTest": overall,
              "heavyTailTest": _tail(test), "parameters": PARAMETERS}
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True); MODEL_DIR.mkdir(parents=True, exist_ok=True)
    (OUTPUT_DIR / "delay_v2_evaluation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT_DIR / "delay_v2_evaluation.txt").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    generic_model, generic_metadata = MODEL_DIR / "delay_model.cbm", MODEL_DIR / "delay_model_metadata.json"
    v1_model, v1_metadata = MODEL_DIR / "delay_model_v1.cbm", MODEL_DIR / "delay_model_v1_metadata.json"
    if generic_model.exists() and not v1_model.exists(): shutil.copy2(generic_model, v1_model)
    if generic_metadata.exists() and not v1_metadata.exists(): shutil.copy2(generic_metadata, v1_metadata)

    deployment_model = _fit_deployment(data)
    v2_model, v2_metadata = MODEL_DIR / "delay_model_v2.cbm", MODEL_DIR / "delay_model_v2_metadata.json"
    deployment_model.save_model(str(v2_model))
    metadata = {"modelVersion": "delay-v2", "createdAtUtc": datetime.now(timezone.utc).isoformat(),
                "targetDefinition": "max(0, departureDelayMin)",
                "targetSource": "revisedDepartureUtc - scheduledDepartureUtc", "targetClampMin": 0,
                "features": FEATURES, "categoricalFeatures": CATEGORICAL_FEATURES,
                "selectedCatBoostParameters": PARAMETERS,
                "historicalFeatureSemantics": "strictly past flights only",
                "temporalCutoffExclusive": CUTOFF.isoformat(), "evaluationSplit": report["split"],
                "evaluationMetrics": report["internalTest"], "evaluationHeavyTail": report["heavyTailTest"],
                "deploymentTrainingRange": _iso_range(data), "deploymentTrainingCount": len(data)}
    v2_metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
