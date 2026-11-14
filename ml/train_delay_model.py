from __future__ import annotations

import json
from datetime import datetime, timezone

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from catboost import CatBoostRegressor

from .baseline import HierarchicalMedianBaseline
from .config import MODEL_DIR, OUTPUT_DIR, RANDOM_SEED
from .data_loader import load_data
from .evaluate import group_mae, regression_metrics
from .features import CATEGORICAL_FEATURES, MODEL_FEATURES, add_features
from .historical_features import HISTORICAL_FEATURES, add_leakage_safe_history
from .preprocessing import chronological_split, prepare_dataset

TRAIN_PROFILE_MIN_COUNT = 5


def _diagnostics_summary(diagnostics: dict) -> dict:
    return {
        "cleanUniqueFlights": diagnostics["cleanUniqueFlights"],
        "availability": diagnostics["availability"],
        "revisedDelayMin": diagnostics["revisedDelayMin"],
        "actualTakeoffDelayMin": diagnostics["actualTakeoffDelayMin"],
        "bothTargetsDifference": diagnostics["bothTargetsDifference"],
        "extremeCounts": {
            target: {threshold: details["count"] for threshold, details in groups.items()}
            for target, groups in diagnostics["extremeDelays"].items()
        },
        "top50DifferenceArtifacts": [
            "ml/output/target_top50_differences.csv",
            "ml/output/target_top50_differences.json"
        ]
    }


def _model_metadata(metrics: dict, created_at: str | None = None) -> dict:
    selected = metrics["selectedModel"]
    return {"modelVersion": "delay-v1", "createdAtUtc": created_at or datetime.now(timezone.utc).isoformat(),
            "targetDefinition": "max(0, departureDelayMin)",
            "targetSource": "revisedDepartureUtc - scheduledDepartureUtc", "targetClampMin": 0,
            "features": selected["features"], "categoricalFeatures": CATEGORICAL_FEATURES,
            "selectedCatBoostParameters": selected["parameters"],
            "trainRange": metrics["split"]["train"], "validationRange": metrics["split"]["validation"],
            "testRange": metrics["split"]["test"], "validationMAE": selected["validation"]["mae"],
            "testMAE": metrics["overallTest"]["model"]["mae"],
            "baselineTestMAE": metrics["overallTest"]["baseline"]["mae"],
            "improvementVsBaselinePct": metrics["overallTest"]["improvementVsBaselinePct"],
            "historicalFeatureSemantics": "strictly past flights only"}


def _range(frame):
    return {"from": frame.scheduledDepartureUtc.min().isoformat(),
            "to": frame.scheduledDepartureUtc.max().isoformat(), "count": len(frame)}


def _improvement(baseline: dict, model: dict) -> float | None:
    return (baseline["mae"] - model["mae"]) / baseline["mae"] * 100 if baseline["mae"] else None


def _subset_metrics(frame, mask) -> dict:
    subset = frame.loc[mask]
    if subset.empty:
        return {"count": 0, "baseline": None, "model": None, "improvementVsBaselinePct": None}
    baseline = regression_metrics(subset.delayTargetMin, subset.baselinePrediction)
    model = regression_metrics(subset.delayTargetMin, subset.prediction)
    return {"count": len(subset), "baseline": baseline, "model": model,
            "improvementVsBaselinePct": _improvement(baseline, model)}


def _tail_metrics(test) -> dict:
    ranges = {"targetLe60": test.delayTargetMin <= 60, "targetLe180": test.delayTargetMin <= 180,
              "targetLe360": test.delayTargetMin <= 360, "targetGt360": test.delayTargetMin > 360}
    return {name: _subset_metrics(test, mask) for name, mask in ranges.items()}


def _fit(train, validation, feature_list, loss_function):
    params = {"iterations": 600, "depth": 6, "learning_rate": .05, "l2_leaf_reg": 5,
              "loss_function": loss_function, "eval_metric": "MAE", "random_seed": RANDOM_SEED,
              "task_type": "CPU", "verbose": False, "allow_writing_files": False}
    model = CatBoostRegressor(**params)
    model.fit(train[feature_list], train.delayTargetMin, cat_features=CATEGORICAL_FEATURES,
              eval_set=(validation[feature_list], validation.delayTargetMin),
              early_stopping_rounds=80, verbose=False)
    prediction = np.maximum(0, model.predict(validation[feature_list]))
    return model, regression_metrics(validation.delayTargetMin, prediction), params


def _plots(test, importance):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    plt.figure(); plt.scatter(test.delayTargetMin, test.prediction, alpha=.45)
    bound = max(float(test.delayTargetMin.max()), float(test.prediction.max()), 1)
    plt.plot([0, bound], [0, bound], "r--"); plt.xlabel("Actual delay (min)"); plt.ylabel("Predicted delay (min)")
    plt.tight_layout(); plt.savefig(OUTPUT_DIR / "actual_vs_predicted.png"); plt.close()
    plt.figure(); plt.hist(test.absoluteError, bins=40); plt.xlabel("Absolute error (min)"); plt.ylabel("Count")
    plt.tight_layout(); plt.savefig(OUTPUT_DIR / "absolute_error_distribution.png"); plt.close()
    labels, values = zip(*sorted(importance.items(), key=lambda item: item[1]))
    plt.figure(figsize=(9, 7)); plt.barh(labels, values); plt.xlabel("Importance")
    plt.tight_layout(); plt.savefig(OUTPUT_DIR / "feature_importance.png"); plt.close()


def _report(metrics: dict) -> str:
    sections = ["Departure delay model methodology report"]
    for title, key in (("Data", "data"), ("Target semantics diagnostics", "targetDiagnostics"),
                       ("Split", "split"), ("Validation comparison", "validationComparison"),
                       ("Selected model", "selectedModel"), ("Overall TEST", "overallTest"),
                       ("Train-supported TEST", "trainSupportedProfiles"),
                       ("Operational-supported TEST", "operationalSupportedProfiles"),
                       ("Heavy-tail descriptive TEST", "heavyTailTest")):
        sections.extend(["", title, json.dumps(metrics[key], ensure_ascii=False, indent=2)])
    sections.extend(["", "Top TEST errors", json.dumps(metrics["topErrors"], ensure_ascii=False, indent=2)])
    return "\n".join(sections) + "\n"


def main() -> None:
    diagnostics_path = OUTPUT_DIR / "target_diagnostics.json"
    if not diagnostics_path.exists():
        raise RuntimeError("Run target diagnostics first: python -m ml.target_diagnostics")
    diagnostics = json.loads(diagnostics_path.read_text(encoding="utf-8"))
    documents, operational_keys = load_data()
    data, data_stats = prepare_dataset(documents)
    data = add_leakage_safe_history(add_features(data))
    train, validation, test = chronological_split(data)
    baseline = HierarchicalMedianBaseline().fit(train)
    baseline_validation = regression_metrics(validation.delayTargetMin, baseline.predict(validation))
    experiments, trained = {}, {}
    experiment_specs = {"scheduleOnlyMAE": (MODEL_FEATURES, "MAE"),
                        "scheduleOnlyRMSE": (MODEL_FEATURES, "RMSE"),
                        "historicalMAE": (MODEL_FEATURES + HISTORICAL_FEATURES, "MAE")}
    for name, (features, loss) in experiment_specs.items():
        fitted, score, params = _fit(train, validation, features, loss)
        experiments[name] = {"features": features, "lossFunction": loss, "validation": score,
                             "bestIteration": fitted.get_best_iteration(), "parameters": params}
        trained[name] = fitted
    selected_name = min(experiments, key=lambda name: experiments[name]["validation"]["mae"])
    selected, model = experiments[selected_name], trained[selected_name]
    features = selected["features"]

    # TEST is evaluated only after selection by validation MAE.
    test = test.copy()
    test["baselinePrediction"] = baseline.predict(test)
    test["prediction"] = np.maximum(0, model.predict(test[features]))
    test["absoluteError"] = (test.delayTargetMin - test.prediction).abs()
    baseline_test = regression_metrics(test.delayTargetMin, test.baselinePrediction)
    model_test = regression_metrics(test.delayTargetMin, test.prediction)
    profile_counts = train.groupby("profileKey").size()
    train_supported_keys = set(profile_counts[profile_counts >= TRAIN_PROFILE_MIN_COUNT].index)
    train_supported = _subset_metrics(test, test.profileKey.isin(train_supported_keys))
    operational_supported = _subset_metrics(test, test.profileKey.isin(operational_keys))
    importance = dict(zip(features, map(float, model.get_feature_importance())))
    top_columns = ["flightId", "scheduledDepartureUtc", "airlineIata", "flightNumber", "originIata",
                   "destinationIata", "delayTargetMin", "prediction", "absoluteError"]
    top_errors = test.nlargest(20, "absoluteError")[top_columns].assign(
        scheduledDepartureUtc=lambda x: x.scheduledDepartureUtc.astype(str)).to_dict("records")
    metrics = {
        "data": data_stats, "split": {"train": _range(train), "validation": _range(validation), "test": _range(test)},
        "targetDiagnostics": _diagnostics_summary(diagnostics),
        "validationComparison": {"baseline": baseline_validation, **experiments},
        "selectedModel": {"name": selected_name, **selected},
        "overallTest": {"baseline": baseline_test, "model": model_test,
                        "improvementVsBaselinePct": _improvement(baseline_test, model_test)},
        "trainSupportedProfiles": {"definition": f"profile count in TRAIN >= {TRAIN_PROFILE_MIN_COUNT}", **train_supported},
        "operationalSupportedProfiles": {"definition": "descriptive Mongo full-history subset", **operational_supported},
        "heavyTailTest": _tail_metrics(test), "topErrors": top_errors,
        "maeByAirline": group_mae(test, "airlineIata", max(5, len(test) // 100)),
        "maeByDepartureWeekday": group_mae(test, "departureWeekday"),
        "maeByDepartureHour": group_mae(test, "departureHour"), "featureImportance": importance}
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True); MODEL_DIR.mkdir(parents=True, exist_ok=True)
    (OUTPUT_DIR / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUTPUT_DIR / "training_report.txt").write_text(_report(metrics), encoding="utf-8")
    _plots(test, importance)
    if selected["validation"]["mae"] < baseline_validation["mae"]:
        model.save_model(str(MODEL_DIR / "delay_model.cbm"))
        metadata = _model_metadata(metrics)
        (MODEL_DIR / "delay_model_metadata.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
