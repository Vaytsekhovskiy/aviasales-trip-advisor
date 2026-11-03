package avia;

import avia.model.*;
import avia.service.*;
import avia.storage.CsvFlightStorage;
import java.lang.reflect.RecordComponent;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Standalone offline checks. Optional argument: local CSV root for the weekly pilot. */
public final class FlightHistoryAggregatorTest {
    private static final FlightHistoryAggregator AGGREGATOR = new FlightHistoryAggregator();
    private static final String KEY = "DP|6525|SVO|OVB";

    public static void main(String[] args) throws Exception {
        long[] dep = {64, 41, 13, 58, 14, 224, 439}, arr = {70, 44, 5, 61, 89, 209, 435};
        List<FlightRecord> week = new ArrayList<>();
        for (int i = 0; i < dep.length; i++) week.add(flight("day" + i, dep[i], arr[i]));
        FlightHistoricalStats stats = AGGREGATOR.aggregate(week).get(KEY);
        check(stats.flightServiceKey().equals(KEY) && stats.sampleSize() == 7, "Service key and sample size");
        close(stats.medianDepartureDelayMin(), 58); close(stats.meanDepartureDelayMin(), 853.0 / 7);
        close(stats.medianArrivalDelayMin(), 70); close(stats.meanArrivalDelayMin(), 913.0 / 7);
        close(stats.departureDelay15Rate(), 5.0 / 7); close(stats.departureDelay30Rate(), 5.0 / 7);
        close(stats.departureDelay60Rate(), 3.0 / 7); close(stats.medianDelayRecoveryMin(), -3);
        check(AGGREGATOR.aggregateFor(week, "DP", "6525", "SVO", "OVB").orElseThrow().equals(stats), "Lookup");
        check(AGGREGATOR.aggregateFor(week, "SU", "6525", "SVO", "OVB").isEmpty(), "Absent service");
        check(AGGREGATOR.aggregate(List.of()).isEmpty(), "Empty input");

        List<FlightRecord> boundaries = List.of(flight("a", -10L, 1L), flight("b", 15L, 2L),
                flight("c", 30L, 3L), flight("d", 60L, 4L), flight("e", 61L, 5L), flight("f", 0L, 6L));
        stats = AGGREGATOR.aggregate(boundaries).get(KEY);
        close(stats.medianDepartureDelayMin(), 22.5); close(stats.meanDepartureDelayMin(), 26);
        close(stats.medianArrivalDelayMin(), 3.5); close(stats.meanArrivalDelayMin(), 3.5);
        close(stats.departureDelay15Rate(), .5); close(stats.departureDelay30Rate(), 2.0 / 6);
        close(stats.departureDelay60Rate(), 1.0 / 6);

        var sparse = List.of(flight("a", null, 12L), flight("b", 20L, null), flight("c", null, null),
                change(flight("cancel", 900L, 900L), "cancelled", true));
        stats = AGGREGATOR.aggregate(sparse).get(KEY);
        check(stats.sampleSize() == 4, "Missing delays still count as flights");
        close(stats.meanDepartureDelayMin(), 20); close(stats.medianDepartureDelayMin(), 20);
        close(stats.meanArrivalDelayMin(), 12); close(stats.medianArrivalDelayMin(), 12);
        close(stats.departureDelay15Rate(), 1); close(stats.cancellationRate(), .25);
        check(stats.medianDelayRecoveryMin() == null, "Recovery requires both delays in same row");
        for (FlightRecord row : List.of(flight("missing", null, null), sparse.get(3))) {
            stats = AGGREGATOR.aggregate(List.of(row)).get(KEY);
            check(stats.meanDepartureDelayMin() == null && stats.medianDepartureDelayMin() == null
                    && stats.meanArrivalDelayMin() == null && stats.medianArrivalDelayMin() == null
                    && stats.departureDelay15Rate() == null && stats.departureDelay30Rate() == null
                    && stats.departureDelay60Rate() == null && stats.medianDelayRecoveryMin() == null, "Undefined metrics");
            close(stats.cancellationRate(), row.cancelled() ? 1 : 0);
        }
        check(AGGREGATOR.aggregate(Arrays.asList(flight(null, 10L, 10L), flight("", 10L, 10L),
                flight("  ", 10L, 10L))).isEmpty(), "Unstable IDs excluded");
        check(AGGREGATOR.aggregate(List.of(change(flight("a", 1L, 1L), "airlineIata", null),
                change(flight("b", 1L, 1L), "flightNumber", " "))).isEmpty(), "Incomplete keys excluded");
        var groups = AGGREGATOR.aggregate(List.of(flight("a", 1L, 1L),
                change(flight("b", 2L, 2L), "airlineIata", "SU"),
                change(flight("c", 3L, 3L), "flightNumber", "6526"),
                change(flight("d", 4L, 4L), "originIata", "LED"),
                change(flight("e", 5L, 5L), "destinationIata", "AER")));
        check(groups.size() == 5 && groups.values().stream().allMatch(s -> s.sampleSize() == 1), "Separate services");

        FlightRecord base = flight("same", 10L, 5L);
        assertWinner(List.of(base, base), base);
        FlightRecord newer = change(change(base, "arrivalDelayMin", null), "collectedAtUtc", Instant.parse("2026-10-05T00:00:00Z"));
        assertWinner(List.of(base, newer), newer); // Newer incomplete row wins, no field merging.
        assertWinner(List.of(change(base, "collectedAtUtc", null), base), base);
        assertWinner(List.of(change(base, "arrivalDelayMin", null), base), base);
        assertWinner(List.of(change(base, "direction", "arrival"), base), base);
        FlightRecord tied = change(base, "departureDelayMin", 99L);
        assertWinner(List.of(base, tied), base.toString().compareTo(tied.toString()) > 0 ? base : tied);
        FlightRecord otherService = change(newer, "flightNumber", "9999");
        assertWinner(List.of(base, otherService), otherService); // Deduplicate before grouping/filtering.
        check(AGGREGATOR.aggregateFor(List.of(base, otherService), "DP", "6525", "SVO", "OVB").isEmpty(), "Lookup after deduplication");
        System.out.println("FlightHistoryAggregatorTest: passed");
        if (args.length > 0) printLocalWeek(Path.of(args[0]));
    }

    private static void assertWinner(List<FlightRecord> rows, FlightRecord expected) {
        var result = AGGREGATOR.aggregate(rows);
        check(result.equals(AGGREGATOR.aggregate(List.of(expected))), "Deduplication winner");
        var reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        check(AGGREGATOR.aggregate(reversed).equals(result), "Input order independence");
        var legacy = new DelayAnalyzer().analyze(rows, LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 3), List.of("SVO"), 1);
        check(legacy.flights().equals(List.of(expected)), "Same strategy as DelayAnalyzer");
    }

    private static void printLocalWeek(Path root) throws Exception {
        var storage = new CsvFlightStorage(root);
        List<FlightRecord> rows = new ArrayList<>();
        int batches = 0;
        for (FlightBatch batch : FlightDatasetPlan.batches(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 3), FlightDatasetPlan.AIRPORTS)) {
            if (Files.exists(storage.pathFor(batch))) { rows.addAll(storage.read(batch)); batches++; }
        }
        System.out.println("Local weekly CSV batches: " + batches);
        System.out.println(AGGREGATOR.aggregateFor(rows, "DP", "6525", "SVO", "OVB"));
    }

    private static FlightRecord flight(String id, Long dep, Long arr) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put("schemaVersion", 1); fields.put("cancelled", false); fields.put("flightId", id);
        fields.put("airlineIata", "DP"); fields.put("flightNumber", "6525");
        fields.put("originIata", "SVO"); fields.put("destinationIata", "OVB");
        fields.put("direction", "departure"); fields.put("status", "Arrived");
        fields.put("departureDelayMin", dep); fields.put("arrivalDelayMin", arr);
        fields.put("scheduledDepartureLocal", LocalDateTime.of(2026, 10, 3, 12, 0));
        fields.put("collectedAtUtc", Instant.parse("2026-10-04T00:00:00Z"));
        return construct(fields);
    }

    private static FlightRecord change(FlightRecord flight, String field, Object value) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        for (RecordComponent c : FlightRecord.class.getRecordComponents()) fields.put(c.getName(), c.getAccessor().invoke(flight));
        fields.put(field, value);
        return construct(fields);
    }

    private static FlightRecord construct(Map<String, Object> fields) throws Exception {
        RecordComponent[] components = FlightRecord.class.getRecordComponents();
        return FlightRecord.class.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new))
                .newInstance(Arrays.stream(components).map(c -> fields.get(c.getName())).toArray());
    }

    private static void close(Double actual, double expected) {
        check(actual != null && Math.abs(actual - expected) < 1e-9, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
