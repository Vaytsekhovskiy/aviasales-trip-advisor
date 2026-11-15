package avia;

import avia.model.*;
import avia.renderer.DelayDashboardHtmlRenderer;
import avia.service.*;
import avia.storage.*;
import org.bson.Document;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Standalone offline behavioral checks; optional raw fixtures generate a clearly labelled preview. */
public final class DelayVisualizationTest {
    public static void main(String[] args) throws Exception {
        List<FlightRecord> flights = List.of(
                flight("a", "SVO", "SU", "2026-09-07T09:00", 10L, 0L, false),
                flight("b", "SVO", "SU", "2026-09-07T09:00", 30L, 20L, false),
                flight("c", "SVO", "SU", "2026-09-07T09:00", 50L, 80L, false),
                flight("d", "LED", "DP", "2026-09-08T10:00", -10L, -20L, false),
                flight("e", "LED", "DP", "2026-09-08T10:00", 60L, 40L, false),
                flight("f", "SVO", "SU", "2026-09-07T09:00", 900L, 900L, true),
                flight("g", "SVO", "DP", "2026-09-07T10:00", null, 5L, false),
                flight("h", null, "ZZ", null, 100L, null, false));
        var aggregator = new DelayVisualizationAggregator(3, 3);
        var data = aggregator.aggregate(flights);
        check(data.weekdays().stream().map(DelayDashboardData.Group::label).toList()
                .equals(Arrays.stream(DayOfWeek.values()).map(Enum::name).toList()), "Calendar weekday order");
        var monday = data.weekdays().get(0);
        close(monday.metrics().medianDepartureDelayMin(), 30); close(monday.metrics().meanDepartureDelayMin(), 30);
        close(monday.metrics().departureDelay30Rate(), 1.0 / 3);
        check(monday.departureN() == 3 && monday.metrics().sampleSize() == 5, "N excludes null and cancelled");
        check(data.heatmap().size() == 168 && data.heatmap().get(9).departureN() == 3
                && data.heatmap().get(10).departureN() == 0 && data.heatmap().get(24 + 10).departureN() == 2, "Local hour grouping");
        check(data.airports().size() == 3 && data.airportCount() == 2, "Airport groups and missing code");
        var svo = data.airports().stream().filter(g -> g.label().equals("SVO")).findFirst().orElseThrow();
        close(svo.metrics().cancellationRate(), .2); close(svo.metrics().medianDepartureDelayMin(), 30);
        check(data.airlines().size() == 1 && data.airlines().get(0).label().equals("SU"), "Airline threshold uses valid N");
        check(data.daily().size() == 2 && data.daily().get(0).label().equals("2026-09-07"), "Chronological grouping");
        close(data.daily().get(1).metrics().medianDepartureDelayMin(), 25);
        check(data.total().departureN() == 6 && data.total().arrivalN() == 6 && data.missingLocalDeparture() == 1, "Independent denominators");
        close(data.total().metrics().medianDepartureDelayMin(), 40); close(data.total().metrics().meanDepartureDelayMin(), 40);
        close(data.total().metrics().cancellationRate(), 1.0 / 8);
        check(data.recovery().points().size() == 5, "Pairwise recovery, cancelled excluded");
        close(data.recovery().median(), 10); close(data.recovery().recoveredShare(), .8); close(data.recovery().worsenedShare(), .2);
        var duplicate = aggregator.aggregate(List.of(flights.get(0), flights.get(0)));
        check(duplicate.total().departureN() == 2, "No extra deduplication");
        var empty = aggregator.aggregate(List.of());
        check(empty.total().departureN() == 0 && empty.total().metrics().medianDepartureDelayMin() == null
                && empty.from() == null && empty.recovery().median() == null && empty.recovery().recoveredShare() == null, "Empty dataset");
        var cancelled = aggregator.aggregate(List.of(flights.get(5)));
        check(cancelled.total().departureN() == 0 && cancelled.recovery().points().isEmpty(), "All cancelled");
        var gap = aggregator.aggregate(List.of(flights.get(0), flight("gap", "SVO", "SU", "2026-09-09T09:00", 1L, 2L, false)));
        check(gap.daily().size() == 3 && gap.daily().get(1).metrics().medianDepartureDelayMin() == null, "Missing day stays null");
        List<FlightRecord> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(flight("id" + i, "SVO", "Air" + i, "2026-09-07T09:00", (long) i, 0L, false));
        var top = new DelayVisualizationAggregator(1, 1).aggregate(many).airlines();
        check(top.size() == 15 && top.get(0).label().equals("Air19") && top.get(14).label().equals("Air5"), "Top15 descending");
        Path temp = Files.createTempFile("dashboard-test-", ".html");
        try {
            var unsafe = aggregator.aggregate(List.of(flight("unsafe", "</script><script>alert(1)</script>", "SU", null, 1L, 2L, false)));
            new DelayDashboardHtmlRenderer().render(temp, unsafe);
            String html = Files.readString(temp);
            check(!html.contains("__DASHBOARD_DATA__") && !html.contains("</script><script>alert")
                    && !html.contains("src=\"http"), "Embedded escaped JSON, no external dependencies");
            new DelayDashboardHtmlRenderer().render(temp, empty);
        } finally { Files.deleteIfExists(temp); }
        System.out.println("DelayVisualizationTest: passed (offline)");
        if (args.length > 0) {
            List<Document> batches = new ArrayList<>();
            for (Path path : AeroDataBoxRawMongoImporter.findFiles(Path.of(args[0])))
                batches.add(MongoAeroDataBoxRawStorage.toDocument(path, Files.readString(path), Instant.now()));
            var normalized = new MongoRawFlightNormalizer().normalize(batches);
            check(normalized.failures().isEmpty(), "Local fixtures normalize");
            var preview = new DelayVisualizationAggregator().aggregate(normalized.flights().stream().map(f -> f.record()).toList(),
                    "OFFLINE PREVIEW · локальные raw fixtures, НЕ чтение MongoDB; время сбора неизвестно");
            Path output = Path.of("output/delay-dashboard-preview.html");
            new DelayDashboardHtmlRenderer().render(output, preview);
            MongoDelayDashboardCli.printSummary(preview);
            System.out.println("Preview: " + output.toAbsolutePath());
        }
    }
    private static FlightRecord flight(String id, String airport, String airline, String date, Long dep, Long arr, boolean cancelled) {
        return MongoFlightReader.fromDocument(new Document("schemaVersion", 1).append("flightId", id).append("originIata", airport)
                .append("airlineIata", airline).append("flightNumber", "1").append("destinationIata", "OVB")
                .append("scheduledDepartureLocal", date).append("departureDelayMin", dep).append("arrivalDelayMin", arr)
                .append("cancelled", cancelled));
    }
    private static void close(Double value, double expected) { check(value != null && Math.abs(value - expected) < 1e-9, "Expected " + expected + ", got " + value); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
