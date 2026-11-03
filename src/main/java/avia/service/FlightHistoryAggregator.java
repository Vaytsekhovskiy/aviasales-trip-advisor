package avia.service;

import avia.model.FlightHistoricalStats;
import avia.model.FlightRecord;
import java.util.*;

/** Independent historical aggregation; no date or status filtering is applied.
 * Rows without a stable ID or a complete service key are excluded.
 */
public final class FlightHistoryAggregator {
    private static final Comparator<FlightRecord> OBSERVATION_ORDER = FlightObservationOrder.COMPARATOR;

    public Map<String, FlightHistoricalStats> aggregate(List<FlightRecord> flights) {
        Map<String, FlightRecord> latest = new TreeMap<>();
        for (FlightRecord flight : flights) {
            if (flight.flightId() == null || flight.flightId().isBlank()) continue;
            latest.merge(flight.flightId(), flight,
                    (a, b) -> OBSERVATION_ORDER.compare(a, b) >= 0 ? a : b);
        }
        Map<String, List<FlightRecord>> groups = new TreeMap<>();
        for (FlightRecord flight : latest.values()) {
            serviceKey(flight.airlineIata(), flight.flightNumber(), flight.originIata(), flight.destinationIata())
                    .ifPresent(key -> groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(flight));
        }
        Map<String, FlightHistoricalStats> result = new TreeMap<>();
        groups.forEach((key, group) -> result.put(key, summarizeObservations(key, group)));
        return Collections.unmodifiableMap(result);
    }

    public Optional<FlightHistoricalStats> aggregateFor(List<FlightRecord> flights, String airlineIata,
            String flightNumber, String originIata, String destinationIata) {
        return serviceKey(airlineIata, flightNumber, originIata, destinationIata)
                .map(key -> aggregate(flights).get(key));
    }

    private static Optional<String> serviceKey(String... parts) {
        for (String part : parts) {
            if (part == null || part.isBlank() || part.contains("|")) return Optional.empty();
        }
        return Optional.of(String.join("|", parts));
    }

    /** Shared metrics for an already selected group. No grouping or deduplication is performed here.
     * The label may identify a service or a visualization group; sampleSize counts all input rows.
     */
    public static FlightHistoricalStats summarizeObservations(String key, List<FlightRecord> flights) {
        List<Double> departure = new ArrayList<>(), arrival = new ArrayList<>(), recovery = new ArrayList<>();
        int cancelled = 0;
        for (FlightRecord flight : flights) {
            if (flight.cancelled()) { cancelled++; continue; }
            Long dep = flight.departureDelayMin(), arr = flight.arrivalDelayMin();
            if (dep != null) departure.add(dep.doubleValue());
            if (arr != null) arrival.add(arr.doubleValue());
            if (dep != null && arr != null) recovery.add(dep.doubleValue() - arr.doubleValue());
        }
        return new FlightHistoricalStats(key, flights.size(), median(departure), mean(departure),
                median(arrival), mean(arrival), rate(departure, 15), rate(departure, 30), rate(departure, 60),
                flights.isEmpty() ? 0 : cancelled / (double) flights.size(), median(recovery));
    }

    private static Double median(List<Double> values) {
        if (values.isEmpty()) return null;
        values.sort(Double::compareTo);
        int mid = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(mid) : values.get(mid - 1) / 2 + values.get(mid) / 2;
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Double rate(List<Double> values, int threshold) {
        return values.isEmpty() ? null : values.stream().filter(v -> v > threshold).count() / (double) values.size();
    }
}
