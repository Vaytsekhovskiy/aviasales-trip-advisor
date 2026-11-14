# Departure delay model

Standalone Python inference for the saved CatBoost `historicalMAE` model. It does not call Java,
write predictions, expose an HTTP API, or modify MongoDB.

## Model contract

- Target: `max(0, departureDelayMin)`.
- Target source: `revisedDepartureUtc - scheduledDepartureUtc`.
- Model: `CatBoostRegressor`, MAE loss. CLI default: `delay-v2`; `delay-v1` remains available.
- Schedule features: airline, flight number, origin, destination, local weekday/hour, route/service keys, weekend flag.
- Historical features: median, sample size and profile delay-over-30 rate at profile/service/route/airline levels.
- Historical semantics: only deduplicated flights with `scheduledDepartureUtc` strictly before the predicted flight.
- Fallback order: global history → airline → route → service → exact profile. Exact sample sizes remain zero when a level is absent.

Revised departure is retained because it is available for the full clean training set. Actual runway departure
is semantically closer to real takeoff delay but is present for only about 46% of clean flights.

The model works best for regular and moderate delays. Extreme irregular events are much harder to predict
without operational, weather, disruption or other external features.

## Configuration

Activate `.venv` and set the read-only MongoDB connection URI:

```powershell
$env:MONGO_URI = "mongodb://127.0.0.1:27017"
```

Required artifacts:

- `models/delay_model_v1.cbm` and `models/delay_model_v1_metadata.json`;
- `models/delay_model_v2.cbm` and `models/delay_model_v2_metadata.json`.

MongoDB sources:

- `avia.flights` for historical features;

Delay prediction is independent from price-data availability. A future flight can receive a delay prediction
even when no price model data is available for the same flight. `predictable_flight_profiles` is not queried
or required by the delay predictor.

For production-sized history, an index on `flights.scheduledDepartureUtc` is recommended. The pipeline does
not create it automatically. MongoDB already indexes `_id` for the supported-profile lookup.

## Request

`scheduledDeparture` must contain a timezone offset. Local weekday and hour are computed before UTC conversion.

```json
{
  "airlineIata": "S7",
  "flightNumber": "5104",
  "originIata": "AER",
  "destinationIata": "OVB",
  "scheduledDeparture": "2026-11-21T13:20:00+03:00"
}
```

Run from a JSON file:

```powershell
python -m ml.predict_delay --json-file request.json
```

Or use explicit arguments:

```powershell
python -m ml.predict_delay --airline S7 --flight-number 5104 --origin AER --destination OVB --scheduled-departure "2026-11-21T13:20:00+03:00"
```

The command uses v2 by default. For an explicit artifact version, add `--model-version v1` or
`--model-version v2`.

Unknown profiles and services are valid. Historical values use the same fallback chain as training:
exact profile → service → route → airline → strictly-past global history. Exact sample-size fields stay zero,
so a fallback median is never presented as evidence of exact-profile observations. `historySupportLevel`
identifies the most specific available level.

## Response and exit codes

The only stdout output is JSON. A successful response contains normalized flight identity, exact unrounded
prediction, model version and historical context. No prediction is persisted.

- `0`: successful prediction;
- `2`: invalid request or unreadable JSON request file;
- `4`: missing model/configuration, MongoDB, or other inference infrastructure error.

Example:

```json
{
  "status": "OK",
  "flight": {
    "airlineIata": "S7",
    "flightNumber": "5104",
    "originIata": "AER",
    "destinationIata": "OVB",
    "scheduledDeparture": "2026-11-21T13:20:00+03:00",
    "profileKey": "S7|5104|AER|OVB|SATURDAY|13"
  },
  "prediction": {
    "predictedDelayMin": 3.780964337240012,
    "modelVersion": "delay-v2"
  },
  "historicalContext": {
    "historySupportLevel": "PROFILE",
    "profileSampleSize": 8,
    "profileMedianDelayMin": 0.0,
    "profileDelayOver30Rate": 0.375,
    "serviceSampleSize": 53,
    "serviceMedianDelayMin": 0.0,
    "routeSampleSize": 192,
    "routeMedianDelayMin": 25.0,
    "airlineSampleSize": 9266,
    "airlineMedianDelayMin": 8.0
  }
}
```

## Verification

```powershell
python -m pytest
```

The explicit-argument command above is also the read-only end-to-end smoke test used for the local profile
`S7|5104|AER|OVB|SATURDAY|13`.
