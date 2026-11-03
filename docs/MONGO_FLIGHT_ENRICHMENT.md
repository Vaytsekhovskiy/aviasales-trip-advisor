# Mongo offer enrichment

Run main class `avia.MongoFlightEnrichmentCli` with no program arguments.
Set `MONGO_URI` in the run configuration environment. No API token is needed.

Reads all `avia.raw_prices_for_dates` snapshots and `avia.flight_statistics`.
Reuses `AviasalesFlightPreprocessor`, `FlightEnrichmentService` and the existing models.
Matching uses only normalized airline, flight number, origin airport and destination airport.
Date, price and collection time are not join keys.

Writes only `avia.enriched_flight_offers`, using the existing unordered bulk writer
(up to 5000 documents per request). Unique `sourceOfferId` is a typed BSON JSON
encoding of the raw `_id`; `sourceRawId` also preserves the original BSON ID for queries.
Other indexes: `flightServiceKey`, `matchStatus`.
Reruns replace the result for the same source document, including newly available
historical statistics. Different price snapshots remain distinct. No source records
are updated or deleted. Orphan target records are not automatically deleted.

Each result retains `rawOffer`, normalized `offer`, source collection time,
`historicalStats`, `matchStatus`, and actual `enrichedAt` time. Historical metrics
are not recalculated or rounded. `departureAt` retains its offset as an ISO string.
`MATCHED` contains history; `NOT_MATCHED` has null history.

Legacy raw records may lack currency, duration or other required model fields even
when their service key is valid. They are retained as `INVALID_OFFER` with the raw
document and a validation explanation, not silently dropped or given fabricated values.
Currency may be read from `_response_metadata.currency`, but is never assumed RUB.
Thus the earlier key-only audit's matched count can exceed the number of fully
parseable matched offers. The CLI reports all three statuses separately.

Offline standalone test: `avia.MongoFlightEnrichmentTest` under `src/test/java`.
It covers matching, missing history, distinct numbers, date-independent matching,
statistics mapping, numeric flight numbers, missing fields, snapshot identity,
and source immutability. No test requires Atlas.
