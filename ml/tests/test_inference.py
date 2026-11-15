import json
from datetime import datetime, timedelta, timezone

import numpy as np
import pytest

from ml.features import MODEL_FEATURES
from ml.historical_features import HISTORICAL_FEATURES
from ml.predictor import (FINAL_FEATURES, DelayPredictor, FutureFlightRequest, InvalidRequestError,
                          PredictorConfigurationError, build_inference_features)


def request(**changes):
    payload = {"airlineIata": " s7 ", "flightNumber": " 5104 ", "originIata": " aer ",
               "destinationIata": "ovb", "scheduledDeparture": "2026-11-21T13:20:00+03:00"}
    payload.update(changes)
    return FutureFlightRequest.from_dict(payload)


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


def test_local_day_is_computed_before_utc_conversion():
    row = request(scheduledDeparture="2026-11-22T00:30:00+14:00").schedule_row()
    assert (row["departureWeekday"], row["departureHour"]) == ("SUNDAY", 0)
    assert row["scheduledDepartureUtc"].weekday() == 5  # Saturday UTC


@pytest.mark.parametrize("changes", [
    {"scheduledDeparture": "2026-11-21T13:20:00"},
    {"originIata": "AE"},
    {"destinationIata": None},
])
def test_invalid_requests_are_rejected(changes):
    with pytest.raises(InvalidRequestError):
        request(**changes)


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
    """Deliberately has no predictable_flight_profiles method or dependency."""
    def __init__(self, documents=None):
        self.documents = documents or []; self.read_count = 0
    def read_before(self, cutoff): self.read_count += 1; return self.documents


class FakeModel:
    feature_names_ = FINAL_FEATURES
    def predict(self, frame):
        assert list(frame.columns) == FINAL_FEATURES
        return np.array([24.7])


def metadata(features=FINAL_FEATURES):
    return {"modelVersion": "delay-v1", "features": features,
            "categoricalFeatures": ["airlineIata", "flightNumber", "originIata",
                                    "destinationIata", "departureWeekday", "routeKey", "serviceKey"]}


def predict_with(documents, future=None):
    return DelayPredictor(FakeRepository(documents), metadata=metadata(), model=FakeModel()).predict(future or request())


def test_exact_profile_history_predicts_without_operational_profile_collection():
    cutoff = request().schedule_row()["scheduledDepartureUtc"]
    result = predict_with([history("a", cutoff - timedelta(days=1), 20)])
    assert result["status"] == "OK"
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


def test_predictor_rejects_metadata_feature_schema_mismatch():
    with pytest.raises(PredictorConfigurationError):
        DelayPredictor(FakeRepository(), metadata=metadata(MODEL_FEATURES), model=FakeModel())


def test_train_inference_feature_order_is_canonical():
    assert FINAL_FEATURES == MODEL_FEATURES + HISTORICAL_FEATURES
