from __future__ import annotations

import pandas as pd
import re

CATEGORICAL_FEATURES = [
    "airlineIata", "flightNumber", "originIata", "destinationIata",
    "departureWeekday", "routeKey", "serviceKey",
]
NUMERIC_FEATURES = ["departureHour", "isWeekend"]
MODEL_FEATURES = CATEGORICAL_FEATURES + NUMERIC_FEATURES
FORBIDDEN_FEATURES = {
    "revisedDepartureUtc", "runwayDepartureUtc", "revisedArrivalUtc", "runwayArrivalUtc",
    "departureDelayMin", "arrivalDelayMin", "delayRecoveryMin", "takeoffDeltaMin",
    "landingDeltaMin", "status", "aircraftReg", "collectedAtUtc",
}


def _compact_upper(value: object, field: str) -> str:
    if value is None:
        raise ValueError(f"{field} is required")
    normalized = re.sub(r"\s+", "", str(value)).upper()
    if not normalized or "|" in normalized:
        raise ValueError(f"Invalid {field}")
    return normalized


def normalize_airline(value: object) -> str:
    normalized = _compact_upper(value, "airlineIata")
    if not re.fullmatch(r"[A-Z0-9]{2,3}", normalized):
        raise ValueError("airlineIata must contain 2-3 ASCII letters/digits")
    return normalized


def normalize_flight_number(value: object) -> str:
    # Historical storage contains one legacy replacement character; preserve existing
    # category semantics here. User-facing validation remains strict in the DTO.
    return _compact_upper(value, "flightNumber")


def normalize_airport(value: object, field: str = "airportIata") -> str:
    normalized = _compact_upper(value, field)
    if not re.fullmatch(r"[A-Z]{3}", normalized):
        raise ValueError(f"{field} must contain exactly 3 ASCII letters")
    return normalized


def normalize_identity(row: pd.Series | dict) -> dict:
    return {"airlineIata": normalize_airline(row["airlineIata"]),
            "flightNumber": normalize_flight_number(row["flightNumber"]),
            "originIata": normalize_airport(row["originIata"], "originIata"),
            "destinationIata": normalize_airport(row["destinationIata"], "destinationIata")}


def route_key(row: pd.Series | dict) -> str:
    return f"{row['originIata']}|{row['destinationIata']}"


def service_key(row: pd.Series | dict) -> str:
    return "|".join(str(row[x]) for x in ("airlineIata", "flightNumber", "originIata", "destinationIata"))


def profile_key(row: pd.Series | dict) -> str:
    return "|".join(str(row[x]) for x in (
        "airlineIata", "flightNumber", "originIata", "destinationIata", "departureWeekday"
    )) + f"|{int(row['departureHour'])}"


def add_features(frame: pd.DataFrame) -> pd.DataFrame:
    result = frame.copy()
    result["airlineIata"] = result["airlineIata"].map(normalize_airline)
    result["flightNumber"] = result["flightNumber"].map(normalize_flight_number)
    result["originIata"] = result["originIata"].map(lambda value: normalize_airport(value, "originIata"))
    result["destinationIata"] = result["destinationIata"].map(lambda value: normalize_airport(value, "destinationIata"))
    result["routeKey"] = result.apply(route_key, axis=1)
    result["serviceKey"] = result.apply(service_key, axis=1)
    result["profileKey"] = result.apply(profile_key, axis=1)
    result["isWeekend"] = result["departureWeekday"].isin(["SATURDAY", "SUNDAY"]).astype(int)
    assert not (set(MODEL_FEATURES) & FORBIDDEN_FEATURES)
    return result
