import json
import math

import pytest

from ml.batch_predict_delay import run_batch
from ml.evaluate import regression_metrics


def test_regression_metrics_include_manual_mse_and_consistent_rmse():
    metrics = regression_metrics([0, 2, 4], [0, 1, 7])
    assert metrics["mae"] == pytest.approx(4 / 3)
    assert metrics["mse"] == pytest.approx(10 / 3)
    assert metrics["rmse"] == pytest.approx(math.sqrt(10 / 3))
    assert metrics["rmse"] == pytest.approx(math.sqrt(metrics["mse"]), rel=1e-12)
    assert metrics["medianAbsoluteError"] == 1


class FakePredictor:
    def predict(self, request):
        if request.flightNumber == "BAD":
            raise RuntimeError("synthetic failure")
        value = float(request.flightNumber)
        row = request.schedule_row()
        return {"status": "OK", "flight": {"airlineIata": request.airlineIata,
                "flightNumber": request.flightNumber, "originIata": request.originIata,
                "destinationIata": request.destinationIata, "scheduledDeparture": request.scheduledDeparture,
                "predictionTimeUtc": request.predictionTimeUtc, "profileKey": row["profileKey"]},
                "prediction": {"predictedDelayMin": value, "modelVersion": "delay-v2"},
                "historicalContext": {"historySupportLevel": "GLOBAL", "profileSampleSize": 0,
                    "profileMedianDelayMin": 0.0, "profileDelayOver30Rate": 0.0,
                    "serviceSampleSize": 0, "serviceMedianDelayMin": 0.0,
                    "routeSampleSize": 0, "routeMedianDelayMin": 0.0,
                    "airlineSampleSize": 0, "airlineMedianDelayMin": 0.0}}


def payload(number):
    return {"airlineIata": "S7", "flightNumber": number, "originIata": "LED",
            "destinationIata": "OVB", "scheduledDeparture": "2026-11-14T13:30:00+03:00",
            "predictionTimeUtc": "2026-11-13T00:00:00Z"}


def test_batch_preserves_success_order_and_isolates_errors():
    report = run_batch([payload("1"), payload("BAD"), payload("2")], FakePredictor())
    assert [row["flightNumber"] for row in report["predictions"]] == ["1", "2"]
    assert report["flightCount"] == 3 and report["successfulCount"] == 2 and report["errorCount"] == 1
    assert all(math.isfinite(row["predictedDelayMin"]) and row["predictedDelayMin"] >= 0
               for row in report["predictions"])
    assert all(row["modelVersion"] == "delay-v2" for row in report["predictions"])
    assert report["actualDelaysIncluded"] is False
    assert all("actualDelay" not in row and "observedActual" not in row for row in report["predictions"])
    json.dumps(report, allow_nan=False)


def test_batch_requires_prediction_time_for_every_row():
    invalid = payload("1")
    del invalid["predictionTimeUtc"]
    report = run_batch([invalid], FakePredictor())
    assert report["successfulCount"] == 0 and report["errorCount"] == 1
    assert "predictionTimeUtc" in report["errors"][0]["error"]
