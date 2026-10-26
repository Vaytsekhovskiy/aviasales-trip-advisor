package avia.service;

import avia.model.FlightRecord;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Deterministic latest-observation deduplication, with explicit sample denominators. */
public final class DelayAnalyzer {
    public record Stats(int count, int cancelled, int departureN, int arrivalN, int recoveryN,
                        Double departureMedian, Double arrivalMedian, Double recoveryMedian,
                        Double over15, Double over30, Double over60, Double cancellationRate) { }
    public record Result(LocalDate from, LocalDate to, List<String> airports, int minSamples,
                         int inputRows, int missingIds, int duplicates, int outOfScope,
                         Map<String, Stats> byAirport, Map<String, Stats> byAirline,
                         Map<String, Stats> byRoute, Map<String, Stats> heatmap,
                         List<FlightRecord> flights) { }

    public Result analyze(List<FlightRecord> rows, LocalDate from, LocalDate to, List<String> airports, int minSamples) {
        if (minSamples < 1 || to.isBefore(from)) throw new IllegalArgumentException("Invalid analysis range or sample threshold");
        Map<String, FlightRecord> latest = new TreeMap<>();
        int missing = 0, duplicates = 0;
        Comparator<FlightRecord> order = FlightObservationOrder.COMPARATOR;
        for (FlightRecord f : rows) {
            if (f.flightId() == null || f.flightId().isBlank()) { missing++; continue; }
            FlightRecord previous = latest.putIfAbsent(f.flightId(), f);
            if (previous != null) {
                duplicates++;
                if (order.compare(f, previous) > 0) latest.put(f.flightId(), f);
            }
        }
        List<FlightRecord> flights = latest.values().stream().filter(f -> airports.contains(f.originIata())
                && f.scheduledDepartureLocal() != null
                && !f.scheduledDepartureLocal().toLocalDate().isBefore(from)
                && !f.scheduledDepartureLocal().toLocalDate().isAfter(to)).toList();
        Map<String, Stats> airportStats = group(flights, FlightRecord::originIata, minSamples);
        for (String airport : airports) airportStats.putIfAbsent(airport, stats(List.of(), minSamples));
        return new Result(from, to, List.copyOf(airports), minSamples, rows.size(), missing, duplicates,
                latest.size() - flights.size(), Collections.unmodifiableMap(airportStats),
                Collections.unmodifiableMap(group(flights, f -> value(f.airlineIata(), "Unknown"), minSamples)),
                Collections.unmodifiableMap(group(flights, f -> f.originIata() + " → " + value(f.destinationIata(), "Unknown"), minSamples)),
                Collections.unmodifiableMap(group(flights, f -> cellKey(f.originIata(), f.scheduledDepartureLocal().getDayOfWeek(),
                        f.scheduledDepartureLocal().getHour()), minSamples)), flights);
    }

    public static String cellKey(String airport, DayOfWeek day, int hour) { return airport + "|" + day + "|" + hour; }
    private static String value(String v, String fallback) { return v == null ? fallback : v; }
    private static Map<String, Stats> group(List<FlightRecord> flights, Function<FlightRecord, String> key, int min) {
        Map<String, List<FlightRecord>> groups = new TreeMap<>();
        for (FlightRecord f : flights) groups.computeIfAbsent(key.apply(f), k -> new ArrayList<>()).add(f);
        Map<String, Stats> result = new TreeMap<>();
        groups.forEach((k, v) -> result.put(k, stats(v, min)));
        return result;
    }
    public static boolean departed(FlightRecord f) {
        return !f.cancelled() && ("Departed".equalsIgnoreCase(f.status()) || "Arrived".equalsIgnoreCase(f.status()));
    }
    private static boolean arrived(FlightRecord f) { return !f.cancelled() && "Arrived".equalsIgnoreCase(f.status()); }
    private static Stats stats(List<FlightRecord> flights, int min) {
        List<Long> dep = new ArrayList<>(), arr = new ArrayList<>(), recovery = new ArrayList<>();
        int cancelled = 0;
        for (FlightRecord f : flights) {
            if (f.cancelled()) cancelled++;
            if (departed(f) && f.departureDelayMin() != null) dep.add(f.departureDelayMin());
            if (arrived(f) && f.arrivalDelayMin() != null) {
                arr.add(f.arrivalDelayMin());
                if (f.departureDelayMin() != null) recovery.add(f.departureDelayMin() - f.arrivalDelayMin());
            }
        }
        return new Stats(flights.size(), cancelled, dep.size(), arr.size(), recovery.size(),
                median(dep, min), median(arr, min), median(recovery, min),
                probability(dep, 15, min), probability(dep, 30, min), probability(dep, 60, min),
                flights.size() < min ? null : cancelled / (double) flights.size());
    }
    private static Double median(List<Long> values, int min) {
        if (values.size() < min) return null;
        values.sort(Long::compareTo);
        int mid = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(mid).doubleValue() : values.get(mid - 1) / 2.0 + values.get(mid) / 2.0;
    }
    private static Double probability(List<Long> values, long threshold, int min) {
        return values.size() < min ? null : values.stream().filter(v -> v > threshold).count() / (double)values.size();
    }
}
