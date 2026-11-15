from __future__ import annotations

from datetime import datetime, timezone
import math
import pandas as pd

REQUIRED = ["flightId", "airlineIata", "flightNumber", "originIata", "destinationIata",
            "departureWeekday", "departureHour", "scheduledDepartureUtc"]
JAVA_RECORD_FIELDS = [
 "schemaVersion","flightId","sourceAirport","direction","flightNumber","airlineIata","airlineIcao","airlineName",
 "originIata","originIcao","originName","originCountry","originTimezone","originLat","originLon","destinationIata",
 "destinationIcao","destinationName","destinationCountry","destinationTimezone","destinationLat","destinationLon",
 "status","cancelled","scheduledDepartureUtc","scheduledDepartureLocal","revisedDepartureUtc","revisedDepartureLocal",
 "runwayDepartureUtc","runwayDepartureLocal","scheduledArrivalUtc","scheduledArrivalLocal","revisedArrivalUtc",
 "revisedArrivalLocal","runwayArrivalUtc","runwayArrivalLocal","departureDelayMin","arrivalDelayMin","delayRecoveryMin",
 "takeoffDeltaMin","landingDeltaMin","departureWeekday","departureHour","arrivalWeekday","arrivalHour","aircraftModel",
 "aircraftReg","departureTerminal","arrivalTerminal","departureGate","arrivalGate","collectedAtUtc"
]


def _present(value) -> bool:
    return value is not None and not (isinstance(value, float) and math.isnan(value))


def _instant(value) -> datetime:
    if not _present(value):
        return datetime.min.replace(tzinfo=timezone.utc)
    ts = pd.Timestamp(value)
    if ts.tzinfo is None:
        ts = ts.tz_localize("UTC")
    return ts.to_pydatetime()


def observation_order(row: dict) -> tuple:
    # Mirrors FlightObservationOrder.COMPARATOR. repr is a deterministic whole-row tie-break.
    completeness = sum(_present(row.get(k)) for k in ("departureDelayMin", "arrivalDelayMin", "scheduledDepartureLocal"))
    whole_row = tuple(repr(row.get(k)) for k in JAVA_RECORD_FIELDS)
    return (_instant(row.get("collectedAtUtc")), completeness, row.get("direction") == "departure", whole_row)


def deduplicate(documents: list[dict]) -> list[dict]:
    latest: dict[str, dict] = {}
    for row in documents:
        flight_id = row.get("flightId")
        if flight_id is None or not str(flight_id).strip():
            continue
        previous = latest.get(str(flight_id))
        if previous is None or observation_order(row) > observation_order(previous):
            latest[str(flight_id)] = row
    return [latest[key] for key in sorted(latest)]


def prepare_dataset(documents: list[dict]) -> tuple[pd.DataFrame, dict]:
    raw = pd.DataFrame(documents)
    raw_delay = pd.to_numeric(raw.get("departureDelayMin", pd.Series(dtype=float)), errors="coerce")
    stats = {
        "rawDocumentCount": len(documents),
        "uniqueFlightIdCount": int(raw.get("flightId", pd.Series(dtype=object)).dropna().astype(str).replace("", pd.NA).dropna().nunique()),
        "missingFlightIdCount": int(sum(not str(x.get("flightId") or "").strip() for x in documents)),
        "cancelledCount": int(raw.get("cancelled", pd.Series(False, index=raw.index)).fillna(False).astype(bool).sum()),
        "nullDepartureDelayCount": int(raw_delay.isna().sum()),
        "negativeDepartureDelayCount": int((raw_delay < 0).sum()),
    }
    deduped = pd.DataFrame(deduplicate(documents))
    stats["afterDeduplicationCount"] = len(deduped)
    if deduped.empty:
        raise ValueError("No rows remain after deduplication")
    delay = pd.to_numeric(deduped.get("departureDelayMin"), errors="coerce")
    valid = ~deduped.get("cancelled", False).fillna(False).astype(bool) & delay.notna()
    for column in REQUIRED:
        valid &= deduped[column].notna() & deduped[column].astype(str).str.strip().ne("")
    clean = deduped.loc[valid].copy()
    clean["departureDelayMin"] = pd.to_numeric(clean["departureDelayMin"])
    clean["delayTargetMin"] = clean["departureDelayMin"].clip(lower=0)
    clean["scheduledDepartureUtc"] = pd.to_datetime(clean["scheduledDepartureUtc"], utc=True, errors="raise")
    clean["departureHour"] = pd.to_numeric(clean["departureHour"], errors="raise").astype(int)
    clean = clean.sort_values(["scheduledDepartureUtc", "flightId"]).reset_index(drop=True)
    stats["afterMlFilteringCount"] = len(clean)
    stats["negativeBeforeClampCount"] = int((clean["departureDelayMin"] < 0).sum())
    stats["delayBeforeClamp"] = describe(clean["departureDelayMin"])
    stats["targetAfterClamp"] = describe(clean["delayTargetMin"])
    return clean, stats


def describe(series: pd.Series) -> dict:
    return {"min": float(series.min()), "median": float(series.median()), "mean": float(series.mean()),
            "p90": float(series.quantile(.90)), "p95": float(series.quantile(.95)),
            "p99": float(series.quantile(.99)), "max": float(series.max())}


def chronological_split(frame: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    if len(frame) < 3:
        raise ValueError("At least three observations are required")
    times = frame["scheduledDepartureUtc"].sort_values().drop_duplicates().tolist()
    if len(times) < 3:
        raise ValueError("At least three distinct departure timestamps are required")
    train_cut = times[min(len(times) - 2, max(1, int(len(times) * .70)))]
    validation_cut = times[min(len(times) - 1, max(2, int(len(times) * .85)))]
    train = frame[frame.scheduledDepartureUtc < train_cut].copy()
    validation = frame[(frame.scheduledDepartureUtc >= train_cut) & (frame.scheduledDepartureUtc < validation_cut)].copy()
    test = frame[frame.scheduledDepartureUtc >= validation_cut].copy()
    if any(x.empty for x in (train, validation, test)):
        raise ValueError("Chronological split produced an empty partition")
    return train, validation, test
