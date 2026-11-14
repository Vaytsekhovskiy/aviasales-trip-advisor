package avia;

import avia.model.*;
import avia.service.*;
import avia.storage.*;
import org.bson.Document;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline normalization/mapping checks, including optional real raw JSON fixtures. No Mongo connection. */
public final class MongoFlightNormalizerTest {
    public static void main(String[] args) throws Exception {
        Document payload = Document.parse(Files.readString(Path.of("src/test/resources/aerodatabox-synthetic.json")));
        Document raw = raw("SVO", "2026-10-03", 0, 12, payload);
        var normalizer = new MongoRawFlightNormalizer();
        var single = normalizer.normalize(List.of(raw));
        check(single.failures().isEmpty() && !single.flights().isEmpty(), "Synthetic preprocessing");
        check(single.observations() == single.flights().size() + single.duplicates() + single.missingIds(), "Counters reconcile");
        var duplicate = normalizer.normalize(List.of(raw, raw));
        check(duplicate.flights().equals(single.flights()), "Duplicate batches select same rows");
        check(duplicate.duplicates() == single.duplicates() * 2 + single.flights().size(), "Duplicate count");
        check(single.flights().stream().allMatch(f -> f.record().collectedAtUtc() == null), "No fabricated collection time");
        var mapped = MongoFlightStorage.toDocument(single.flights().get(0));
        for (var component : FlightRecord.class.getRecordComponents()) {
            check(mapped.containsKey(component.getName()), "Preserved component " + component.getName());
        }
        check(mapped.get("scheduledDepartureUtc") instanceof Date, "BSON UTC date");
        check(mapped.get("scheduledDepartureLocal") instanceof String, "Local time has no invented offset");
        check(mapped.get("sourceBatchKeys").equals(List.of("SVO|2026-10-03|00|12")), "Traceability");
        check(mapped.containsKey("collectedAtUtc") && mapped.get("collectedAtUtc") == null, "Unknown collection time");

        Document newer = new Document(raw).append("collected_at_utc", Date.from(Instant.parse("2026-10-05T00:00:00Z")));
        var expected = normalizer.normalize(List.of(newer));
        check(normalizer.normalize(List.of(raw, newer)).flights().equals(expected.flights()), "Later collection wins");
        check(normalizer.normalize(List.of(newer, raw)).flights().equals(expected.flights()), "Order independent");
        var secondBatch = raw("SVO", "2026-10-03", 12, 24, payload);
        var traced = normalizer.normalize(List.of(raw, secondBatch));
        check(traced.flights().stream().allMatch(f -> f.sourceBatchKeys().size() == 2), "All source keys retained");
        var invalid = new Document(raw).append("payload", new Document("invalid", true));
        var failed = normalizer.normalize(List.of(invalid, raw));
        check(failed.failures().size() == 1 && failed.flights().equals(single.flights()), "Continue parsing after bad batch");
        check(normalizer.normalize(List.of()).flights().isEmpty(), "Empty source");
        System.out.println("MongoFlightNormalizerTest: passed (offline)");
        if (args.length > 0) {
            List<Document> batches = new ArrayList<>();
            for (Path path : AeroDataBoxRawMongoImporter.findFiles(Path.of(args[0]))) {
                batches.add(MongoAeroDataBoxRawStorage.toDocument(path, Files.readString(path), Instant.now()));
            }
            var result = normalizer.normalize(batches);
            check(result.failures().isEmpty(), "Real fixtures parsed");
            var dp = result.flights().stream().map(MongoFlightStorage::toDocument)
                    .filter(d -> "DP|6525|SVO|OVB".equals(d.getString("flightServiceKey"))).toList();
            check(dp.size() == 7, "Weekly DP6525: seven physical flights");
            check(dp.stream().map(d -> d.getString("flightId")).distinct().count() == 7, "Distinct physical IDs");
            System.out.printf("Offline raw fixtures: batches=%d, observations=%d, unique=%d, duplicates=%d, missingIds=%d%n",
                    result.batches(), result.observations(), result.flights().size(), result.duplicates(), result.missingIds());
            System.out.println("DP|6525|SVO|OVB: " + dp.size());
            for (Document d : dp) System.out.println(d.getString("flightId") + ": departure=" + d.get("departureDelayMin") + ", arrival=" + d.get("arrivalDelayMin"));
        }
    }

    private static Document raw(String airport, String date, int from, int to, Document payload) {
        return new Document("airport", airport).append("date", date).append("from_hour", from).append("to_hour", to)
                .append("batch_key", String.format(Locale.ROOT, "%s|%s|%02d|%02d", airport, date, from, to))
                .append("ingested_at", new Date()).append("payload", payload);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
