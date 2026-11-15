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
import org.bson.Document;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline BSON round-trip and aggregator integration; no Atlas required. */
public final class MongoFlightStatisticsTest {
    public static void main(String[] args) throws Exception {
        var payload = Files.readString(Path.of("src/test/resources/aerodatabox-synthetic.json"));
        var raw = MongoAeroDataBoxRawStorage.toDocument(Path.of("2026-10-03/SVO_00-12.json"), payload, Instant.now());
        var normalized = new MongoRawFlightNormalizer().normalize(List.of(raw));
        check(!normalized.flights().isEmpty(), "Fixture has flights");
        for (var flight : normalized.flights()) {
            Document doc = MongoFlightStorage.toDocument(flight);
            check(MongoFlightReader.fromDocument(doc).equals(flight.record()), "All fields round-trip");
        }
        Document base = MongoFlightStorage.toDocument(normalized.flights().get(0));
        base.put("departureDelayMin", 15); // Int32 is also accepted for a Long field.
        base.put("collectedAtUtc", Date.from(Instant.parse("2026-10-25T12:00:00Z")));
        check(MongoFlightReader.fromDocument(base).departureDelayMin() == 15L, "BSON numeric conversion");
        check(MongoFlightReader.fromDocument(base).collectedAtUtc().equals(Instant.parse("2026-10-25T12:00:00Z")), "BSON date");
        for (Document bad : List.of(new Document(base).append("cancelled", null),
                new Document(base).append("schemaVersion", 2), new Document(base).append("departureDelayMin", 1.5))) {
            try { MongoFlightReader.fromDocument(bad); throw new AssertionError("Malformed document accepted"); }
            catch (RuntimeException expected) { }
        }

        long[] departures = {64, 41, 13, 58, 14, 224, 439}, arrivals = {70, 44, 5, 61, 89, 209, 435};
        List<FlightRecord> week = new ArrayList<>();
        for (int i = 0; i < departures.length; i++) {
            var doc = new Document(base).append("flightId", "physical-" + i)
                    .append("airlineIata", "DP").append("flightNumber", "6525")
                    .append("originIata", "SVO").append("destinationIata", "OVB")
                    .append("cancelled", false).append("departureDelayMin", departures[i]).append("arrivalDelayMin", arrivals[i]);
            week.add(MongoFlightReader.fromDocument(doc));
        }
        var aggregator = new FlightHistoryAggregator();
        var stats = aggregator.aggregate(week).get("DP|6525|SVO|OVB");
        verifyControl(stats);
        Instant time = Instant.parse("2026-10-25T12:00:00Z");
        Document stored = MongoFlightStatisticsStorage.toDocument(stats, time);
        check(stored.get("computedAt").equals(Date.from(time)), "BSON computedAt");
        check(stored.getString("flightNumber").equals("6525") && stored.getString("originIata").equals("SVO"), "Key components");
        check(stored.getDouble("meanDepartureDelayMin").equals(stats.meanDepartureDelayMin()), "No rounding");
        var sparse = aggregator.aggregate(List.of(MongoFlightReader.fromDocument(new Document(base)
                .append("departureDelayMin", null).append("arrivalDelayMin", null)))).values().iterator().next();
        check(MongoFlightStatisticsStorage.toDocument(sparse, time).get("departureDelay15Rate") == null, "Null metrics preserved");
        Map<String, Document> simulated = new HashMap<>();
        simulated.put(stats.flightServiceKey(), stored);
        simulated.put(stats.flightServiceKey(), MongoFlightStatisticsStorage.toDocument(stats, time.plusSeconds(1)));
        check(simulated.size() == 1, "Repeated key mapping");
        week.addAll(new ArrayList<>(week)); // Existing physical IDs cannot inflate sample size.
        check(aggregator.aggregate(week).get(stats.flightServiceKey()).sampleSize() == 7, "Dedup still applied");
        week.add(MongoFlightReader.fromDocument(new Document(base).append("flightId", "new-day")
                .append("airlineIata", "DP").append("flightNumber", "6525").append("originIata", "SVO")
                .append("destinationIata", "OVB").append("cancelled", false)));
        check(aggregator.aggregate(week).get(stats.flightServiceKey()).sampleSize() == 8, "New physical flight updates sample");
        System.out.println("MongoFlightStatisticsTest: passed (offline; Atlas upsert not executed)");

        if (args.length > 0) {
            List<Document> batches = new ArrayList<>();
            for (Path path : AeroDataBoxRawMongoImporter.findFiles(Path.of(args[0]))) {
                batches.add(MongoAeroDataBoxRawStorage.toDocument(path, Files.readString(path), time));
            }
            var result = new MongoRawFlightNormalizer().normalize(batches);
            check(result.failures().isEmpty(), "Real raw fixtures normalize");
            List<FlightRecord> read = result.flights().stream().map(MongoFlightStorage::toDocument)
                    .map(MongoFlightReader::fromDocument).toList();
            var calculated = aggregator.aggregate(read);
            verifyControl(calculated.get("DP|6525|SVO|OVB"));
            Map<String, Document> documents = new HashMap<>();
            calculated.forEach((key, value) -> documents.put(key, MongoFlightStatisticsStorage.toDocument(value, time)));
            calculated.forEach((key, value) -> documents.put(key, MongoFlightStatisticsStorage.toDocument(value, time.plusSeconds(1))));
            check(documents.size() == calculated.size(), "Repeat mapping retains cardinality");
            System.out.println("Offline BSON fixture pipeline: flights=" + read.size() + ", services=" + calculated.size()
                    + ", mapped documents after two passes=" + documents.size());
            System.out.println(documents.get("DP|6525|SVO|OVB").toJson());
        }
    }

    private static void verifyControl(FlightHistoricalStats s) {
        check(s.sampleSize() == 7, "Seven flights");
        close(s.medianDepartureDelayMin(), 58); close(s.meanDepartureDelayMin(), 853.0 / 7);
        close(s.medianArrivalDelayMin(), 70); close(s.meanArrivalDelayMin(), 913.0 / 7);
        close(s.departureDelay15Rate(), 5.0 / 7); close(s.departureDelay30Rate(), 5.0 / 7);
        close(s.departureDelay60Rate(), 3.0 / 7); close(s.cancellationRate(), 0); close(s.medianDelayRecoveryMin(), -3);
    }
    private static void close(Double value, double expected) { check(value != null && Math.abs(value - expected) < 1e-9, "Metric " + expected); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
