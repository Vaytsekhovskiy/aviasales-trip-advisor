from __future__ import annotations

import os
from pathlib import Path

DATABASE = "avia"
FLIGHTS_COLLECTION = "flights"
PROFILES_COLLECTION = "predictable_flight_profiles"
RANDOM_SEED = 42
ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "models"
OUTPUT_DIR = Path(__file__).resolve().parent / "output"


def mongo_uri() -> str:
    value = os.environ.get("MONGO_URI", "").strip()
    if not value:
        raise RuntimeError("MONGO_URI is required (for example mongodb://127.0.0.1:27017)")
    return value
