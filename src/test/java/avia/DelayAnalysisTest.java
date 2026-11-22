package avia;

import avia.model.*;
import avia.service.*;
import avia.storage.*;
import avia.renderer.DelayHtmlRenderer;
import org.json.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline behavioral checks for CSV round-trip, deduplication, denominators and sparse reports. */
public final class DelayAnalysisTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 3);
    private static final FlightBatch BATCH = new FlightBatch("SVO", DATE, LocalTime.MIDNIGHT, LocalTime.NOON);

    public static void main(String[] args) throws Exception {
        List<FlightRecord> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(flight(100+i, (i+1)*10, "Arrived", false));
        rows.add(flight(100, 50, "Arrived", true)); // Latest observation wins, not both.
        rows.add(flight(900, 800, "Canceled", false));
        rows.add(flight(901, 900, "CheckIn", false)); // Estimate must not inflate delays.
        JSONObject missing = json(902, 10, "Arrived");
        missing.remove("airline");
        rows.add(normalize(missing, false));
        JSONObject outside = json(903, 10, "Arrived");
        outside.getJSONObject("departure").getJSONObject("scheduledTime").put("local", "2026-10-04T01:00+03:00");
        rows.add(normalize(outside, false));
        var analyzer = new DelayAnalyzer();
        var result = analyzer.analyze(rows, DATE, DATE, FlightDatasetPlan.AIRPORTS, 1);
        var svo = result.byAirport().get("SVO");
        check(result.duplicates() == 1 && result.missingIds() == 1 && result.outOfScope() == 1, "Exclusion counters");
        check(svo.count() == 6 && svo.departureN() == 4 && svo.cancelled() == 1, "Cancellation/estimate handling");
        check(svo.departureMedian() == 35 && svo.over15() == 1 && svo.over30() == .5 && svo.over60() == 0, "Even median and strict threshold denominators");
        check(Math.abs(svo.cancellationRate() - 1.0/6) < 1e-9, "Cancellation denominator");
        Collections.reverse(rows);
        check(analyzer.analyze(rows, DATE, DATE, FlightDatasetPlan.AIRPORTS, 1).equals(result), "Order-independent deduplication");
        check(analyzer.analyze(rows, DATE, DATE, FlightDatasetPlan.AIRPORTS, 5).byAirport().get("SVO").departureMedian() == null, "Small samples suppressed");
        check(result.byAirport().get("LED").count() == 0 && result.byAirport().get("LED").departureMedian() == null, "Missing airport not zero delay");
        check(FlightDatasetPlan.batches(DATE.minusDays(6), DATE, FlightDatasetPlan.AIRPORTS).size() == 70, "Five-airport weekly plan");
        Path directory = Files.createTempDirectory("delay-analysis-");
        var storage = new CsvFlightStorage(directory);
        Path html = directory.resolve("report.html");
        try {
            storage.save(rows, BATCH);
            check(storage.read(BATCH).equals(rows), "All 52 fields CSV round-trip including multiline quoted airline");
            new DelayHtmlRenderer().render(html, result, 1, 10);
            String output = Files.readString(html);
            for (String airport : FlightDatasetPlan.AIRPORTS) check(output.contains("data-airport='"+airport+"'"), "Five airport cards");
            check(output.contains("N=4") && output.contains("35.0"), "Heatmap values and counts");
            Files.writeString(storage.pathFor(BATCH), "schema_version,flight_id\n1,test\n");
            try { storage.read(BATCH); throw new AssertionError("Malformed schema accepted"); }
            catch (java.io.IOException expected) { }
        } finally {
            Files.deleteIfExists(html); Files.deleteIfExists(storage.pathFor(BATCH));
            Files.deleteIfExists(directory.resolve(DATE.toString())); Files.deleteIfExists(directory);
        }
        System.out.println("DelayAnalysisTest: passed");
    }

    private static FlightRecord flight(int number, int delay, String status, boolean later) throws Exception {
        return normalize(json(number, delay, status), later);
    }
    private static JSONObject json(int number, int delay, String status) throws Exception {
        JSONObject f = new JSONObject(Files.readString(Path.of("src/test/resources/aerodatabox-synthetic.json")))
                .getJSONArray("departures").getJSONObject(0);
        f.put("number", "SU " + number).put("status", status);
        f.getJSONObject("airline").put("name", "Test, \"Air\"\nMultiline");
        f.getJSONObject("departure").getJSONObject("revisedTime").put("utc", Instant.parse("2026-10-02T22:00:00Z").plusSeconds(delay*60L).toString());
        return f;
    }
    private static FlightRecord normalize(JSONObject f, boolean later) {
        return new FlightPreprocessor().process(new JSONObject().put("departures", new JSONArray().put(f)).toString(),
                BATCH, Instant.parse(later ? "2026-10-05T00:00:00Z" : "2026-10-04T00:00:00Z")).get(0);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
