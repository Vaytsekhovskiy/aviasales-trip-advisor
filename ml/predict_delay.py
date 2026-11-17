from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .config import MODEL_DIR
from .predictor import (DelayPredictor, FlightPredictionRequest, InvalidRequestError,
                        MongoHistoryRepository, PredictorConfigurationError)

EXIT_INVALID_REQUEST = 2
EXIT_UNSUPPORTED_PROFILE = 3
EXIT_INFRASTRUCTURE_ERROR = 4


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Predict revised departure delay in minutes")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--json-file", type=Path)
    source.add_argument("--airline")
    parser.add_argument("--flight-number"); parser.add_argument("--origin")
    parser.add_argument("--destination"); parser.add_argument("--scheduled-departure")
    parser.add_argument("--prediction-time-utc")
    parser.add_argument("--model-version", choices=("v1", "v2"), default="v2")
    return parser


def _payload(args) -> dict:
    if args.json_file:
        return json.loads(args.json_file.read_text(encoding="utf-8"))
    return {"airlineIata": args.airline, "flightNumber": args.flight_number,
            "originIata": args.origin, "destinationIata": args.destination,
            "scheduledDeparture": args.scheduled_departure,
            "predictionTimeUtc": args.prediction_time_utc}


def _emit(value: dict) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False))


def main(argv: list[str] | None = None) -> int:
    repository = None
    try:
        args = _parser().parse_args(argv)
        request = FlightPredictionRequest.from_dict(_payload(args))
        repository = MongoHistoryRepository()
        result = DelayPredictor(repository, model_path=MODEL_DIR / f"delay_model_{args.model_version}.cbm",
                                metadata_path=MODEL_DIR / f"delay_model_{args.model_version}_metadata.json").predict(request)
        _emit(result)
        return 0 if result["status"] == "OK" else EXIT_UNSUPPORTED_PROFILE
    except (InvalidRequestError, json.JSONDecodeError, OSError) as error:
        _emit({"status": "INVALID_REQUEST", "message": str(error)})
        return EXIT_INVALID_REQUEST
    except Exception as error:
        if not isinstance(error, PredictorConfigurationError):
            error = PredictorConfigurationError(str(error))
        _emit({"status": "ERROR", "message": str(error)})
        return EXIT_INFRASTRUCTURE_ERROR
    finally:
        if repository is not None:
            repository.close()


if __name__ == "__main__":
    raise SystemExit(main())
