from __future__ import annotations

import heapq
from collections import defaultdict
from dataclasses import dataclass, field

import pandas as pd

HISTORICAL_FEATURES = [
    "historicalProfileMedianDelay", "historicalProfileSampleSize",
    "historicalServiceMedianDelay", "historicalServiceSampleSize",
    "historicalRouteMedianDelay", "historicalRouteSampleSize",
    "historicalAirlineMedianDelay", "historicalAirlineSampleSize",
    "historicalProfileDelayOver30Rate",
]


@dataclass
class RunningStats:
    lower: list[float] = field(default_factory=list)
    upper: list[float] = field(default_factory=list)
    over30: int = 0

    def add(self, value: float) -> None:
        if not self.lower or value <= -self.lower[0]:
            heapq.heappush(self.lower, -value)
        else:
            heapq.heappush(self.upper, value)
        if len(self.lower) > len(self.upper) + 1:
            heapq.heappush(self.upper, -heapq.heappop(self.lower))
        elif len(self.upper) > len(self.lower):
            heapq.heappush(self.lower, -heapq.heappop(self.upper))
        self.over30 += value > 30

    @property
    def count(self) -> int:
        return len(self.lower) + len(self.upper)

    @property
    def median(self) -> float:
        return -self.lower[0] if len(self.lower) > len(self.upper) else (-self.lower[0] + self.upper[0]) / 2

    @property
    def rate(self) -> float:
        return self.over30 / self.count


def resolve_historical_features(stores: dict, global_stats: RunningStats, keys: dict) -> dict[str, float]:
    """Shared train/inference fallback: global -> airline -> route -> service -> profile."""
    own = {name: stores[name].get(key, RunningStats()) for name, key in keys.items()}
    broader = global_stats.median if global_stats.count else 0.0
    resolved = {}
    for name in ("airline", "route", "service", "profile"):
        stats = own[name]
        resolved[name] = stats.median if stats.count else broader
        if stats.count:
            broader = stats.median
    output = {}
    for name in ("profile", "service", "route", "airline"):
        title = name.capitalize()
        output[f"historical{title}MedianDelay"] = resolved[name]
        output[f"historical{title}SampleSize"] = own[name].count
    output["historicalProfileDelayOver30Rate"] = next(
        (own[name].rate for name in ("profile", "service", "route", "airline") if own[name].count),
        global_stats.rate if global_stats.count else 0.0)
    return output


def aggregate_historical_features(frame: pd.DataFrame, keys: dict) -> dict[str, float]:
    stores = {name: {} for name in ("profile", "service", "route", "airline")}
    global_stats = RunningStats()
    for _, row in frame.iterrows():
        target = max(0.0, float(row["departureDelayMin"]))
        row_keys = {"profile": row["profileKey"], "service": row["serviceKey"],
                    "route": row["routeKey"], "airline": row["airlineIata"]}
        global_stats.add(target)
        for name, key in row_keys.items():
            stats = stores[name].setdefault(key, RunningStats())
            stats.add(target)
    return resolve_historical_features(stores, global_stats, keys)


def add_leakage_safe_history(frame: pd.DataFrame) -> pd.DataFrame:
    """Compute features from strictly earlier scheduled timestamps only."""
    result = frame.sort_values(["scheduledDepartureUtc", "flightId"]).copy()
    stores = {name: defaultdict(RunningStats) for name in ("profile", "service", "route", "airline")}
    global_stats = RunningStats()
    output: dict[str, list[float]] = {name: [] for name in HISTORICAL_FEATURES}
    indices: list = []
    for _, batch in result.groupby("scheduledDepartureUtc", sort=True):
        pending = []
        for index, row in batch.iterrows():
            keys = {"profile": row["profileKey"], "service": row["serviceKey"],
                    "route": row["routeKey"], "airline": row["airlineIata"]}
            resolved = resolve_historical_features(stores, global_stats, keys)
            for name in HISTORICAL_FEATURES:
                output[name].append(resolved[name])
            indices.append(index); pending.append((keys, float(row.delayTargetMin)))
        for keys, target in pending:
            global_stats.add(target)
            for name, key in keys.items():
                stores[name][key].add(target)
    features = pd.DataFrame(output, index=indices)
    for column in HISTORICAL_FEATURES:
        result[column] = features[column]
    return result.sort_index()
