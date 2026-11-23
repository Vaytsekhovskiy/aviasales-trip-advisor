from __future__ import annotations

import json
import math
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Protocol

import pandas as pd
from catboost import CatBoostRegressor
from pymongo import MongoClient

from .config import DATABASE, FLIGHTS_COLLECTION, MODEL_DIR, PROFILES_COLLECTION, mongo_uri
from .features import (CATEGORICAL_FEATURES, MODEL_FEATURES, add_features, normalize_airline,
                       normalize_airport, normalize_flight_number, profile_key, route_key, service_key)
from .historical_features import HISTORICAL_FEATURES, aggregate_historical_features
from .preprocessing import prepare_dataset

FINAL_FEATURES = MODEL_FEATURES + HISTORICAL_FEATURES


class InvalidRequestError(ValueError):
    pass


class PredictorConfigurationError(RuntimeError):
    pass


@dataclass(frozen=True)
class FlightPredictionRequest:
    airlineIata: str
    flightNumber: str
    originIata: str
    destinationIata: str
    scheduledDeparture: str
    predictionTimeUtc: str
    scheduledLocal: datetime
    predictionTime: datetime

    @classmethod
    def from_dict(cls, payload: dict) -> "FlightPredictionRequest":
        required = ("airlineIata", "flightNumber", "originIata", "destinationIata",
                    "scheduledDeparture", "predictionTimeUtc")
        missing = [name for name in required if name not in payload or payload[name] is None]
        if missing:
            raise InvalidRequestError("Missing required field(s): " + ", ".join(missing))
        try:
            scheduled_text = str(payload["scheduledDeparture"]).strip()
            scheduled = datetime.fromisoformat(scheduled_text)
            if scheduled.tzinfo is None or scheduled.utcoffset() is None:
                raise InvalidRequestError("scheduledDeparture must include a timezone offset")
            prediction_text = str(payload["predictionTimeUtc"]).strip()
            prediction_time = datetime.fromisoformat(prediction_text)
            if prediction_time.tzinfo is None or prediction_time.utcoffset() is None:
                raise InvalidRequestError("predictionTimeUtc must include a UTC timezone offset")
            if prediction_time.utcoffset().total_seconds() != 0:
                raise InvalidRequestError("predictionTimeUtc must be a UTC timestamp (Z or +00:00)")
            if prediction_time.astimezone(timezone.utc) >= scheduled.astimezone(timezone.utc):
                raise InvalidRequestError("predictionTimeUtc must be earlier than scheduledDeparture")
            flight_number = normalize_flight_number(payload["flightNumber"])
            if not flight_number.isascii() or not flight_number.isalnum() or len(flight_number) > 8:
                raise InvalidRequestError("flightNumber must contain 1-8 ASCII letters/digits")
            return cls(normalize_airline(payload["airlineIata"]), flight_number,
                       normalize_airport(payload["originIata"], "originIata"),
                       normalize_airport(payload["destinationIata"], "destinationIata"),
                       scheduled_text, prediction_text, scheduled, prediction_time)
        except InvalidRequestError:
            raise
        except (TypeError, ValueError) as error:
            raise InvalidRequestError(str(error)) from error

    def schedule_row(self) -> dict:
        # Derive weekday/hour before UTC conversion: these are local schedule features.
        row = {"airlineIata": self.airlineIata, "flightNumber": self.flightNumber,
               "originIata": self.originIata, "destinationIata": self.destinationIata,
               "departureWeekday": self.scheduledLocal.strftime("%A").upper(),
               "departureHour": self.scheduledLocal.hour}
        row["routeKey"] = route_key(row); row["serviceKey"] = service_key(row)
        row["profileKey"] = profile_key(row); row["isWeekend"] = int(self.scheduledLocal.weekday() >= 5)
        row["scheduledDepartureUtc"] = self.scheduledLocal.astimezone(timezone.utc)
        return row


class PredictionRepository(Protocol):
    def supports_profile(self, profile_key: str) -> bool: ...
    def read_before(self, cutoff_utc: datetime) -> list[dict]: ...


class MongoHistoryRepository:
    def __init__(self, uri: str | None = None):
        self.client = MongoClient(uri or mongo_uri(), serverSelectionTimeoutMS=5000)
        self.db = self.client[DATABASE]

    def read_before(self, cutoff_utc: datetime) -> list[dict]:
        return list(self.db[FLIGHTS_COLLECTION].find({"scheduledDepartureUtc": {"$lt": cutoff_utc}}))

    def supports_profile(self, profile_key: str) -> bool:
        return self.db[PROFILES_COLLECTION].find_one({"_id": profile_key}, {"_id": 1}) is not None

    def close(self) -> None:
        self.client.close()


def build_inference_features(request: FlightPredictionRequest, documents: list[dict]) -> tuple[pd.DataFrame, dict]:
    schedule = request.schedule_row()
    eligible = [row for row in documents if row.get("scheduledDepartureUtc") is not None]
    if eligible:
        history, _ = prepare_dataset(eligible)
        history = history[history.scheduledDepartureUtc < pd.Timestamp(schedule["scheduledDepartureUtc"])]
        history = add_features(history)
    else:
        history = pd.DataFrame(columns=["departureDelayMin", "profileKey", "serviceKey", "routeKey", "airlineIata"])
    keys = {"profile": schedule["profileKey"], "service": schedule["serviceKey"],
            "route": schedule["routeKey"], "airline": schedule["airlineIata"]}
    historical = aggregate_historical_features(history, keys)
    feature_row = {**schedule, **historical}
    return pd.DataFrame([{name: feature_row[name] for name in FINAL_FEATURES}], columns=FINAL_FEATURES), feature_row


class DelayPredictor:
    def __init__(self, repository: PredictionRepository, model_path: Path | None = None,
                 metadata_path: Path | None = None, model=None, metadata: dict | None = None):
        self.repository = repository
        self.model_path = model_path or MODEL_DIR / "delay_model_v2.cbm"
        self.metadata_path = metadata_path or MODEL_DIR / "delay_model_v2_metadata.json"
        if metadata is None:
            try:
                metadata = json.loads(self.metadata_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as error:
                raise PredictorConfigurationError(f"Cannot load model metadata: {error}") from error
        self.metadata = metadata
        if self.metadata.get("features") != FINAL_FEATURES:
            raise PredictorConfigurationError("Metadata feature schema does not match inference feature schema")
        if self.metadata.get("categoricalFeatures") != CATEGORICAL_FEATURES:
            raise PredictorConfigurationError("Metadata categorical feature schema mismatch")
        if model is None:
            try:
                model = CatBoostRegressor()
                model.load_model(str(self.model_path))
            except Exception as error:
                raise PredictorConfigurationError(f"Cannot load CatBoost model: {error}") from error
        model_names = list(getattr(model, "feature_names_", []) or [])
        if model_names and model_names != FINAL_FEATURES:
            raise PredictorConfigurationError("CatBoost artifact feature schema mismatch")
        self.model = model

    def predict(self, request: FlightPredictionRequest) -> dict:
        flight = request.schedule_row()
        flight_result = {"airlineIata": request.airlineIata, "flightNumber": request.flightNumber,
                         "originIata": request.originIata, "destinationIata": request.destinationIata,
                         "scheduledDeparture": request.scheduledDeparture,
                         "predictionTimeUtc": request.predictionTimeUtc,
                         "profileKey": flight["profileKey"]}
        if not self.repository.supports_profile(flight["profileKey"]):
            return {"status": "UNSUPPORTED_PROFILE", "supportedProfile": False,
                    "flight": flight_result,
                    "message": "Flight is not available for joint price and delay prediction"}
        documents = self.repository.read_before(flight["scheduledDepartureUtc"])
        features, context = build_inference_features(request, documents)
        prediction = float(self.model.predict(features)[0])
        if not math.isfinite(prediction):
            raise PredictorConfigurationError("Model returned a non-finite prediction")
        prediction = max(0.0, prediction)
        support_level = next((level for level, key in (("PROFILE", "historicalProfileSampleSize"),
                              ("SERVICE", "historicalServiceSampleSize"), ("ROUTE", "historicalRouteSampleSize"),
                              ("AIRLINE", "historicalAirlineSampleSize")) if context[key] > 0), "GLOBAL")
        return {"status": "OK", "supportedProfile": True, "flight": flight_result,
                "prediction": {"predictedDelayMin": prediction,
                               "modelVersion": self.metadata["modelVersion"]},
                "historicalContext": {
                    "historySupportLevel": support_level,
                    "profileSampleSize": int(context["historicalProfileSampleSize"]),
                    "profileMedianDelayMin": context["historicalProfileMedianDelay"],
                    "profileDelayOver30Rate": context["historicalProfileDelayOver30Rate"],
                    "serviceSampleSize": int(context["historicalServiceSampleSize"]),
                    "serviceMedianDelayMin": context["historicalServiceMedianDelay"],
                    "routeSampleSize": int(context["historicalRouteSampleSize"]),
                    "routeMedianDelayMin": context["historicalRouteMedianDelay"],
                    "airlineSampleSize": int(context["historicalAirlineSampleSize"]),
                    "airlineMedianDelayMin": context["historicalAirlineMedianDelay"]}}


# Backward-compatible import name for low-level tooling; this is the same DTO class,
# not a second request schema.
FutureFlightRequest = FlightPredictionRequest
