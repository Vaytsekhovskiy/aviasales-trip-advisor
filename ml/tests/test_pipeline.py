from datetime import datetime, timedelta, timezone

import pandas as pd
import pytest

from ml.baseline import HierarchicalMedianBaseline
from ml.features import FORBIDDEN_FEATURES, MODEL_FEATURES, add_features, profile_key
from ml.historical_features import add_leakage_safe_history
from ml.preprocessing import chronological_split, deduplicate, prepare_dataset


BASE = {"flightId": "f1", "airlineIata": "S7", "flightNumber": "5104", "originIata": "AER",
        "destinationIata": "OVB", "departureWeekday": "SATURDAY", "departureHour": 13,
        "scheduledDepartureUtc": datetime(2026, 1, 1, tzinfo=timezone.utc), "scheduledDepartureLocal": "2026-01-01T16:00:00",
        "collectedAtUtc": datetime(2025, 12, 1, tzinfo=timezone.utc), "departureDelayMin": 10,
        "cancelled": False, "direction": "departure"}


def row(**changes):
    return {**BASE, **changes}


def test_deduplication_selects_latest_whole_observation():
    old, new = row(departureDelayMin=90), row(departureDelayMin=5, collectedAtUtc=BASE["collectedAtUtc"] + timedelta(hours=1))
    assert deduplicate([old, new]) == [new]


@pytest.mark.parametrize("change", [{"cancelled": True}, {"departureDelayMin": None}])
def test_filter_cancelled_and_null_target(change):
    frame, _ = prepare_dataset([row(**change), row(flightId="ok")])
    assert frame.flightId.tolist() == ["ok"]


def test_negative_delay_is_clamped_without_changing_source():
    frame, stats = prepare_dataset([row(departureDelayMin=-8)])
    assert frame.iloc[0].departureDelayMin == -8
    assert frame.iloc[0].delayTargetMin == 0
    assert stats["negativeBeforeClampCount"] == 1


def test_keys_and_profile_compatibility():
    frame = add_features(pd.DataFrame([row()]))
    assert frame.iloc[0].routeKey == "AER|OVB"
    assert frame.iloc[0].serviceKey == "S7|5104|AER|OVB"
    assert profile_key(frame.iloc[0]) == "S7|5104|AER|OVB|SATURDAY|13"


def baseline_train():
    return pd.DataFrame([
        {"serviceKey": "s1", "routeKey": "r1", "airlineIata": "a1", "departureWeekday": "MONDAY", "departureHour": 1, "delayTargetMin": 10},
        {"serviceKey": "s1", "routeKey": "r1", "airlineIata": "a1", "departureWeekday": "TUESDAY", "departureHour": 2, "delayTargetMin": 20},
        {"serviceKey": "s2", "routeKey": "r1", "airlineIata": "a1", "departureWeekday": "MONDAY", "departureHour": 1, "delayTargetMin": 30},
        {"serviceKey": "s3", "routeKey": "r2", "airlineIata": "a1", "departureWeekday": "MONDAY", "departureHour": 1, "delayTargetMin": 40},
    ])


@pytest.mark.parametrize("query,expected", [
    ({"serviceKey":"s1","routeKey":"r1","airlineIata":"a1","departureWeekday":"MONDAY","departureHour":1}, 10),
    ({"serviceKey":"s1","routeKey":"r1","airlineIata":"a1","departureWeekday":"FRIDAY","departureHour":9}, 15),
    ({"serviceKey":"missing","routeKey":"r1","airlineIata":"a1","departureWeekday":"FRIDAY","departureHour":9}, 20),
    ({"serviceKey":"missing","routeKey":"missing","airlineIata":"a1","departureWeekday":"FRIDAY","departureHour":9}, 25),
    ({"serviceKey":"missing","routeKey":"missing","airlineIata":"missing","departureWeekday":"FRIDAY","departureHour":9}, 25),
])
def test_baseline_fallback_hierarchy(query, expected):
    assert HierarchicalMedianBaseline().fit(baseline_train()).predict_one(query) == expected


def test_chronological_split_keeps_timestamps_together():
    times = pd.to_datetime([f"2026-01-{day:02d}T00:00:00Z" for day in range(1, 11)])
    frame = pd.DataFrame({"scheduledDepartureUtc": list(times) + [times[6]], "flightId": range(11)})
    train, validation, test = chronological_split(frame)
    assert train.scheduledDepartureUtc.max() < validation.scheduledDepartureUtc.min()
    assert validation.scheduledDepartureUtc.max() < test.scheduledDepartureUtc.min()
    assert not (set(train.scheduledDepartureUtc) & set(validation.scheduledDepartureUtc))


def test_model_features_have_no_post_flight_columns():
    assert not set(MODEL_FEATURES) & FORBIDDEN_FEATURES


def test_historical_features_use_strictly_earlier_timestamps():
    source = pd.DataFrame([
        row(flightId="a", departureDelayMin=10, delayTargetMin=10),
        row(flightId="b", departureDelayMin=100, delayTargetMin=100),
        row(flightId="c", scheduledDepartureUtc=BASE["scheduledDepartureUtc"] + timedelta(hours=1),
            departureDelayMin=30, delayTargetMin=30),
    ])
    result = add_leakage_safe_history(add_features(source))
    assert result.loc[result.flightId == "a", "historicalProfileSampleSize"].item() == 0
    assert result.loc[result.flightId == "b", "historicalProfileSampleSize"].item() == 0
    later = result.loc[result.flightId == "c"].iloc[0]
    assert later.historicalProfileSampleSize == 2
    assert later.historicalProfileMedianDelay == 55
