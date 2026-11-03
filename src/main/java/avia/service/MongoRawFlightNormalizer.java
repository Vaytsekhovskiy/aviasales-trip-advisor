package avia.service;

import avia.model.FlightBatch;
import avia.model.FlightRecord;
import org.bson.Document;
import java.time.*;
import java.util.*;

/** Pure adapter from raw Mongo batches to the existing preprocessor and shared ordering. */
public final class MongoRawFlightNormalizer {
    public record Flight(FlightRecord record, List<String> sourceBatchKeys) { }
    public record Result(int batches, int observations, int duplicates, int missingIds,
                         int batchesWithoutCollectionTime, List<Flight> flights, List<String> failures) { }

    public Result normalize(Iterable<Document> rawBatches) {
        FlightPreprocessor preprocessor = new FlightPreprocessor();
        Map<String, FlightRecord> latest = new TreeMap<>();
        Map<String, SortedSet<String>> sources = new TreeMap<>();
        List<String> failures = new ArrayList<>();
        int batches = 0, observations = 0, duplicates = 0, missing = 0, unknownTime = 0;
        for (Document raw : rawBatches) {
            batches++;
            try {
                String airport = raw.getString("airport");
                LocalDate date = LocalDate.parse(raw.getString("date"));
                int from = raw.getInteger("from_hour"), to = raw.getInteger("to_hour");
                if (to < 1 || to > 24) throw new IllegalArgumentException("Invalid end hour");
                FlightBatch batch = new FlightBatch(airport, date, LocalTime.of(from, 0),
                        to == 24 ? LocalTime.MIDNIGHT : LocalTime.of(to, 0));
                String key = String.format(Locale.ROOT, "%s|%s|%02d|%02d", batch.airport(), date, from, to);
                if (!key.equals(raw.getString("batch_key"))) throw new IllegalArgumentException("Batch key mismatch");
                // Only explicit source observation metadata is trusted. Never use ingested_at or file mtime.
                Object timestamp = raw.get("collected_at_utc");
                Instant collectedAt = timestamp == null ? null : timestamp instanceof Date d
                        ? d.toInstant() : Instant.parse((String) timestamp);
                List<FlightRecord> rows = preprocessor.process(raw.get("payload", Document.class).toJson(), batch, collectedAt);
                if (collectedAt == null) unknownTime++;
                observations += rows.size();
                for (FlightRecord row : rows) {
                    if (row.flightId() == null || row.flightId().isBlank()) { missing++; continue; }
                    FlightRecord previous = latest.putIfAbsent(row.flightId(), row);
                    if (previous != null) {
                        duplicates++;
                        if (FlightObservationOrder.COMPARATOR.compare(row, previous) > 0) latest.put(row.flightId(), row);
                    }
                    sources.computeIfAbsent(row.flightId(), ignored -> new TreeSet<>()).add(key);
                }
            } catch (RuntimeException e) {
                failures.add("Raw batch #" + batches + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        List<Flight> flights = latest.values().stream()
                .map(row -> new Flight(row, List.copyOf(sources.get(row.flightId())))).toList();
        return new Result(batches, observations, duplicates, missing, unknownTime, flights, List.copyOf(failures));
    }
}
