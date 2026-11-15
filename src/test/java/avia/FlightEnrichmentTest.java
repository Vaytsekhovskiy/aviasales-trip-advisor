package avia;
import avia.common.aerodatabox.*;
import avia.common.aerodatabox.cli.*;
import avia.common.aerodatabox.mongo.*;
import avia.common.aviasales.*;
import avia.common.aviasales.cli.*;
import avia.common.flight.*;
import avia.common.flight.model.*;
import avia.common.flight.mongo.*;
import avia.common.flight.storage.*;
import avia.common.mongo.*;
import avia.task1.analysis.*;
import avia.task1.model.*;
import avia.task1.report.*;
import avia.task2.analysis.*;
import avia.task2.model.*;
import avia.task2.report.*;
import avia.task3.analysis.*;
import avia.task3.cli.*;
import avia.task3.model.*;
import avia.task3.mongo.*;
import avia.task3.report.*;
import avia.task3.service.*;
import avia.task4.analysis.*;
import avia.task4.report.*;
import avia.task5.delay.model.*;
import avia.task5.delay.mongo.*;
import avia.task5.delay.service.*;
import avia.task5.price.model.*;
import avia.task5.price.service.*;
import avia.common.flight.storage.CsvFlightStorage;
import java.lang.reflect.RecordComponent;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.json.*;

/** Standalone offline join checks; optional CSV root validates the real weekly pilot. */
public final class FlightEnrichmentTest {
    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of("src/test/resources/aviasales-prices-for-dates.json"));
        var parser = new AviasalesFlightPreprocessor();
        var offers = parser.process(json);
        check(offers.size() == 1, "One offer parsed");
        var offer = offers.get(0);
        check(offer.flightServiceKey().equals("DP|6525|SVO|OVB"), "Exact service key");
        check(offer.price() == 4773 && offer.currency().equals("RUB"), "Price and top-level currency");
        check(offer.transfers() == 0 && offer.durationMin() == 245, "Duration and transfers");
        check(offer.departureAt().equals(OffsetDateTime.parse("2026-10-26T22:45:00+03:00")), "Offset preserved");
        var normalized = new AviasalesFlightOffer(" dp ", " dp 6525 ", " svo ", " ovb ",
                offer.departureAt(), 4773, " rub ", 0, 245);
        check(normalized.equals(offer), "Whitespace, case and airline prefix normalized");

        var history = new FlightHistoryAggregator().aggregate(syntheticWeek());
        var service = new FlightEnrichmentService();
        var enriched = service.enrich(offers, history).get(0);
        verifyMatched(enriched);
        check(enriched.offer().equals(offer), "Original offer preserved");
        var absent = service.enrich(offers, Map.of()).get(0);
        check(absent.historicalStats() == null && absent.matchStatus() == EnrichedFlightOffer.MatchStatus.NOT_MATCHED,
                "Unmatched offer retained");
        var future = new AviasalesFlightOffer("DP", "6525", "SVO", "OVB", offer.departureAt().plusDays(30).plusHours(2),
                9000, "RUB", 0, 245);
        check(service.enrich(List.of(future), history).get(0).historicalStats().equals(enriched.historicalStats()),
                "Different date, time and price still match");
        for (String field : List.of("flight_number", "airline", "origin_airport", "destination_airport")) {
            JSONObject changed = new JSONObject(json);
            changed.getJSONArray("data").getJSONObject(0).put(field, switch (field) {
                case "flight_number" -> "6526";
                case "airline" -> "SU";
                case "origin_airport" -> "LED";
                default -> "AER";
            });
            var other = parser.process(changed.toString()).get(0);
            var results = service.enrich(List.of(offer, other, offer), history);
            check(results.size() == 3 && results.get(1).historicalStats() == null
                    && results.get(0).equals(results.get(2)), "Exact join and preserved order: " + field);
            for (Object invalid : List.of("", "   ", JSONObject.NULL)) {
                JSONObject broken = new JSONObject(json);
                broken.getJSONArray("data").getJSONObject(0).put(field, invalid);
                check(parser.process(broken.toString()).isEmpty(), "Invalid key skipped: " + field);
            }
            JSONObject missing = new JSONObject(json);
            missing.getJSONArray("data").getJSONObject(0).remove(field);
            check(parser.process(missing.toString()).isEmpty(), "Missing key skipped: " + field);
        }
        for (String field : List.of("departure_at", "price", "duration", "transfers")) {
            JSONObject broken = new JSONObject(json);
            broken.getJSONArray("data").getJSONObject(0).put(field, "invalid");
            broken.getJSONArray("data").put(new JSONObject(json).getJSONArray("data").getJSONObject(0));
            check(parser.process(broken.toString()).equals(offers), "Malformed row does not discard valid rows");
        }
        check(service.enrich(List.of(), history).isEmpty(), "Empty offers");
        try {
            parser.process("{\"success\":false,\"currency\":\"rub\",\"data\":[]}");
            throw new AssertionError("Failed API response accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("FlightEnrichmentTest: passed");
        if (args.length > 0) {
            var storage = new CsvFlightStorage(Path.of(args[0]));
            List<FlightRecord> rows = new ArrayList<>();
            for (var batch : FlightDatasetPlan.batches(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 3), FlightDatasetPlan.AIRPORTS)) {
                rows.addAll(storage.read(batch));
            }
            var real = service.enrich(offers, new FlightHistoryAggregator().aggregate(rows)).get(0);
            verifyMatched(real);
            System.out.println("Local CSV verification: passed; match=" + real.matchStatus());
            System.out.println(real);
        }
    }

    private static void verifyMatched(EnrichedFlightOffer result) {
        check(result.matchStatus() == EnrichedFlightOffer.MatchStatus.MATCHED, "Matched by service key");
        var stats = result.historicalStats();
        check(stats.sampleSize() == 7, "Seven physical flights");
        close(stats.medianDepartureDelayMin(), 58); close(stats.meanDepartureDelayMin(), 853.0 / 7);
        close(stats.medianArrivalDelayMin(), 70); close(stats.meanArrivalDelayMin(), 913.0 / 7);
        close(stats.departureDelay15Rate(), 5.0 / 7); close(stats.departureDelay30Rate(), 5.0 / 7);
        close(stats.departureDelay60Rate(), 3.0 / 7); close(stats.cancellationRate(), 0);
        close(stats.medianDelayRecoveryMin(), -3);
    }

    private static List<FlightRecord> syntheticWeek() throws Exception {
        long[] dep = {64, 41, 13, 58, 14, 224, 439}, arr = {70, 44, 5, 61, 89, 209, 435};
        List<FlightRecord> rows = new ArrayList<>();
        RecordComponent[] components = FlightRecord.class.getRecordComponents();
        var constructor = FlightRecord.class.getDeclaredConstructor(Arrays.stream(components)
                .map(RecordComponent::getType).toArray(Class<?>[]::new));
        for (int i = 0; i < dep.length; i++) {
            Map<String, Object> fields = new HashMap<>();
            fields.put("schemaVersion", 1); fields.put("cancelled", false); fields.put("flightId", "day" + i);
            fields.put("airlineIata", "DP"); fields.put("flightNumber", "6525");
            fields.put("originIata", "SVO"); fields.put("destinationIata", "OVB");
            fields.put("scheduledDepartureLocal", LocalDateTime.of(2026, 9, 27, 21, 0).plusDays(i));
            fields.put("departureDelayMin", dep[i]); fields.put("arrivalDelayMin", arr[i]);
            FlightRecord row = constructor.newInstance(Arrays.stream(components).map(c -> fields.get(c.getName())).toArray());
            rows.add(row); rows.add(row); // The join consumes deduplicated historical statistics.
        }
        return rows;
    }

    private static void close(Double actual, double expected) {
        check(actual != null && Math.abs(actual - expected) < 1e-9, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
