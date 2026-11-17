import json
from datetime import datetime, timedelta, timezone

import numpy as np
import pytest

from ml.features import MODEL_FEATURES
from ml.historical_features import HISTORICAL_FEATURES
from ml.predictor import (FINAL_FEATURES, DelayPredictor, FlightPredictionRequest, InvalidRequestError,
                          PredictorConfigurationError, build_inference_features)


def request(**changes):
    payload = {"airlineIata": " s7 ", "flightNumber": " 5104 ", "originIata": " aer ",
               "destinationIata": "ovb", "scheduledDeparture": "2026-11-21T13:20:00+03:00",
               "predictionTimeUtc": "2026-11-17T11:20:00Z"}
    payload.update(changes)
    return FlightPredictionRequest.from_dict(payload)


def history(flight_id, scheduled, delay, **changes):
    row = {"flightId": flight_id, "airlineIata": "S7", "flightNumber": "5104", "originIata": "AER",
           "destinationIata": "OVB", "departureWeekday": "SATURDAY", "departureHour": 13,
           "scheduledDepartureUtc": scheduled, "scheduledDepartureLocal": "2026-11-01T13:00:00",
           "collectedAtUtc": scheduled - timedelta(days=1), "departureDelayMin": delay,
           "cancelled": False, "direction": "departure"}
    row.update(changes)
    return row


def test_valid_request_is_normalized_and_local_schedule_is_preserved():
    value = request()
    row = value.schedule_row()
    assert (value.airlineIata, value.originIata) == ("S7", "AER")
    assert (row["departureWeekday"], row["departureHour"], row["isWeekend"]) == ("SATURDAY", 13, 1)
    assert row["routeKey"] == "AER|OVB"
    assert row["serviceKey"] == "S7|5104|AER|OVB"
    assert row["profileKey"] == "S7|5104|AER|OVB|SATURDAY|13"
    assert value.predictionTimeUtc == "2026-11-17T11:20:00Z"


def test_local_day_is_computed_before_utc_conversion():
    row = request(scheduledDeparture="2026-11-22T00:30:00+14:00").schedule_row()
    assert (row["departureWeekday"], row["departureHour"]) == ("SUNDAY", 0)
    assert row["scheduledDepartureUtc"].weekday() == 5  # Saturday UTC


@pytest.mark.parametrize("changes", [
    {"scheduledDeparture": "2026-11-21T13:20:00"},
    {"predictionTimeUtc": None},
    {"predictionTimeUtc": "2026-11-17T11:20:00"},
    {"predictionTimeUtc": "2026-11-17T14:20:00+03:00"},
    {"predictionTimeUtc": "2026-11-21T10:20:00Z"},
    {"predictionTimeUtc": "2026-11-21T10:20:01Z"},
    {"originIata": "AE"},
    {"destinationIata": None},
])
def test_invalid_requests_are_rejected(changes):
    with pytest.raises(InvalidRequestError):
        request(**changes)


def test_missing_prediction_time_is_rejected():
    payload = {"airlineIata": "S7", "flightNumber": "5104", "originIata": "AER",
               "destinationIata": "OVB", "scheduledDeparture": "2026-11-21T13:20:00+03:00"}
    with pytest.raises(InvalidRequestError, match="predictionTimeUtc"):
        FlightPredictionRequest.from_dict(payload)


def test_history_cutoff_dedup_target_clamp_and_aggregates():
    future = request()
    cutoff = future.schedule_row()["scheduledDepartureUtc"]
    docs = [
        history("a", cutoff - timedelta(days=3), -5),
        history("b", cutoff - timedelta(days=2), 40),
        history("b", cutoff - timedelta(days=2), 60,
                collectedAtUtc=cutoff - timedelta(hours=1)),  # latest observation wins
        history("at", cutoff, 999),
        history("after", cutoff + timedelta(minutes=1), 999),
    ]
    features, context = build_inference_features(future, docs)
    assert list(features.columns) == FINAL_FEATURES
    assert context["historicalProfileSampleSize"] == 2
    assert context["historicalProfileMedianDelay"] == 30  # median(0, 60)
    assert context["historicalProfileDelayOver30Rate"] == .5
    assert context["historicalServiceSampleSize"] == 2
    assert context["historicalRouteSampleSize"] == 2
    assert context["historicalAirlineSampleSize"] == 2


class FakeRepository:
    def __init__(self, documents=None, supported_profiles=None):
        self.documents = documents or []; self.read_count = 0; self.profile_read_count = 0
        self.supported_profiles = set(supported_profiles or [])
    def supports_profile(self, profile_key):
        self.profile_read_count += 1
        return profile_key in self.supported_profiles
    def read_before(self, cutoff): self.read_count += 1; return self.documents


class FakeModel:
    feature_names_ = FINAL_FEATURES
    def __init__(self): self.predict_count = 0
    def predict(self, frame):
        self.predict_count += 1
        assert list(frame.columns) == FINAL_FEATURES
        return np.array([24.7])


def metadata(features=FINAL_FEATURES):
    return {"modelVersion": "delay-v1", "features": features,
            "categoricalFeatures": ["airlineIata", "flightNumber", "originIata",
                                    "destinationIata", "departureWeekday", "routeKey", "serviceKey"]}


def predict_with(documents, future=None):
    future = future or request()
    repository = FakeRepository(documents, {future.schedule_row()["profileKey"]})
    return DelayPredictor(repository, metadata=metadata(), model=FakeModel()).predict(future)


def test_exact_profile_history_predicts_for_supported_common_profile():
    cutoff = request().schedule_row()["scheduledDepartureUtc"]
    result = predict_with([history("a", cutoff - timedelta(days=1), 20)])
    assert result["status"] == "OK"
    assert result["supportedProfile"] is True
    assert result["flight"]["predictionTimeUtc"] == "2026-11-17T11:20:00Z"
    assert result["prediction"]["predictedDelayMin"] == 24.7
    assert result["historicalContext"]["historySupportLevel"] == "PROFILE"


def test_missing_profile_falls_back_to_service_and_still_predicts():
    cutoff = request().schedule_row()["scheduledDepartureUtc"]
    result = predict_with([history("a", cutoff - timedelta(days=1), 40,
                                   departureWeekday="FRIDAY", departureHour=12)])
    context = result["historicalContext"]
    assert result["status"] == "OK" and context["profileSampleSize"] == 0
    assert context["profileMedianDelayMin"] == 40
    assert context["historySupportLevel"] == "SERVICE"


def test_missing_service_falls_back_to_route():
    cutoff = request().schedule_row()["scheduledDepartureUtc"]
    result = predict_with([history("a", cutoff - timedelta(days=1), 35, flightNumber="999")])
    context = result["historicalContext"]
    assert context["serviceSampleSize"] == 0 and context["routeSampleSize"] == 1
    assert context["profileMedianDelayMin"] == 35 and context["historySupportLevel"] == "ROUTE"


def test_missing_route_falls_back_to_airline():
    cutoff = request().schedule_row()["scheduledDepartureUtc"]
    result = predict_with([history("a", cutoff - timedelta(days=1), 30,
                                   flightNumber="999", originIata="LED", destinationIata="PEE")])
    context = result["historicalContext"]
    assert context["routeSampleSize"] == 0 and context["airlineSampleSize"] == 1
    assert context["profileMedianDelayMin"] == 30 and context["historySupportLevel"] == "AIRLINE"


def test_completely_unseen_identity_uses_global_fallback():
    future = request(airlineIata="DP", flightNumber="1569", originIata="LED", destinationIata="PEE")
    cutoff = future.schedule_row()["scheduledDepartureUtc"]
    result = predict_with([history("a", cutoff - timedelta(days=1), 25)], future)
    context = result["historicalContext"]
    assert all(context[name] == 0 for name in ("profileSampleSize", "serviceSampleSize",
                                                "routeSampleSize", "airlineSampleSize"))
    assert context["profileMedianDelayMin"] == 25
    assert context["historySupportLevel"] == "GLOBAL"
    assert np.isfinite(result["prediction"]["predictedDelayMin"])


def test_unsupported_profile_returns_before_history_and_model_prediction():
    future = request(airlineIata="DP", flightNumber="1569", originIata="LED", destinationIata="PEE",
                     scheduledDeparture="2026-11-21T00:40:00+03:00")
    repository = FakeRepository([history("a", datetime(2026, 11, 1, tzinfo=timezone.utc), 20)])
    model = FakeModel()
    result = DelayPredictor(repository, metadata=metadata(), model=model).predict(future)
    assert result == {
        "status": "UNSUPPORTED_PROFILE",
        "supportedProfile": False,
        "flight": {
            "airlineIata": "DP", "flightNumber": "1569", "originIata": "LED",
            "destinationIata": "PEE", "scheduledDeparture": "2026-11-21T00:40:00+03:00",
            "predictionTimeUtc": "2026-11-17T11:20:00Z",
            "profileKey": "DP|1569|LED|PEE|SATURDAY|0",
        },
        "message": "Flight is not available for joint price and delay prediction",
    }
    assert repository.profile_read_count == 1
    assert repository.read_count == 0
    assert model.predict_count == 0


def test_predictor_rejects_metadata_feature_schema_mismatch():
    with pytest.raises(PredictorConfigurationError):
        DelayPredictor(FakeRepository(), metadata=metadata(MODEL_FEATURES), model=FakeModel())


def test_train_inference_feature_order_is_canonical():
    assert FINAL_FEATURES == MODEL_FEATURES + HISTORICAL_FEATURES
