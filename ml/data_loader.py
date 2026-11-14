from __future__ import annotations

from pymongo import MongoClient

from .config import DATABASE, FLIGHTS_COLLECTION, PROFILES_COLLECTION, mongo_uri


def load_data() -> tuple[list[dict], set[str]]:
    """Read only; this module never writes to MongoDB."""
    with MongoClient(mongo_uri()) as client:
        db = client[DATABASE]
        flights = list(db[FLIGHTS_COLLECTION].find({}))
        profile_keys = {str(row["_id"]) for row in db[PROFILES_COLLECTION].find({}, {"_id": 1})}
    return flights, profile_keys
