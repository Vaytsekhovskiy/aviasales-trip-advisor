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
import avia.task5.model.*;
import avia.task5.price.service.*;
import avia.common.aerodatabox.mongo.MongoAeroDataBoxRawStorage;
import org.bson.Document;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Standalone offline tests; no MongoClient is created. */
public final class AeroDataBoxRawMongoImporterTest {
    public static void main(String[] args) throws Exception {
        var first = MongoAeroDataBoxRawStorage.parsePath(Path.of("data/raw/2026-10-03/SVO_00-12.json"));
        check(first.airport().equals("SVO") && first.date().toString().equals("2026-10-03")
                && first.fromHour() == 0 && first.toHour() == 12
                && first.batchKey().equals("SVO|2026-10-03|00|12"), "First path");
        check(MongoAeroDataBoxRawStorage.parsePath(Path.of("data/raw/2026-09-27/OVB_12-24.json"))
                .batchKey().equals("OVB|2026-09-27|12|24"), "Midnight end");
        for (String invalid : List.of("2026-02-30/SVO_00-12.json", "2026-10-03/SVO_12-12.json",
                "2026-10-03/SVO_00-25.json", "2026-10-03/SVO_00-24.json", "2026-10-03/unknown.json")) {
            try { MongoAeroDataBoxRawStorage.parsePath(Path.of(invalid)); throw new AssertionError("Accepted " + invalid); }
            catch (RuntimeException expected) { }
        }
        String json = "{\"arrivals\":[],\"departures\":[{\"number\":\"DP 6525\",\"extra\":null}],\"custom\":{\"flag\":true}}";
        Instant now = Instant.parse("2026-10-25T12:00:00Z");
        Document doc = MongoAeroDataBoxRawStorage.toDocument(Path.of("2026-10-03/SVO_00-12.json"), json, now);
        check(doc.get("payload") instanceof Document, "Nested BSON document");
        check(doc.get("payload", Document.class).equals(Document.parse(json)), "Original payload preserved");
        check(doc.get("payload", Document.class).get("departures") instanceof List<?>, "Nested BSON array");
        check(doc.getDate("ingested_at").toInstant().equals(now) && !doc.containsKey("collected_at"), "Import timestamp only");
        check(doc.getString("source").equals("AERODATABOX") && doc.getString("endpoint").equals("FIDS"), "Source metadata");

        Path root = Files.createTempDirectory("raw-import-test-");
        Path date = Files.createDirectory(root.resolve("2026-10-03"));
        List<Path> created = new ArrayList<>();
        try {
            for (String name : List.of("SVO_00-12.json", "OVB_12-24.json", "LED_00-12.json", "AER_00-12.json", "invalid.json", "ignored.txt")) {
                Path file = date.resolve(name);
                Files.writeString(file, name.startsWith("LED") ? "not json" : json);
                created.add(file);
            }
            var files = AeroDataBoxRawMongoImporter.findFiles(root);
            check(files.size() == 5, "Recursive JSON discovery");
            Map<String, Document> saved = new HashMap<>();
            Predicate<Document> sink = d -> {
                if (d.getString("airport").equals("AER")) throw new IllegalStateException("Simulated write failure");
                return saved.put(d.getString("batch_key"), d) == null;
            };
            var initial = AeroDataBoxRawMongoImporter.importFiles(files, sink);
            check(initial.inserted() == 2 && initial.updated() == 0 && initial.failures().size() == 3,
                    "Continues after invalid path, JSON and storage failure");
            var repeated = AeroDataBoxRawMongoImporter.importFiles(files, sink);
            check(repeated.inserted() == 0 && repeated.updated() == 2 && saved.size() == 2, "Repeat replaces by key");
        } finally {
            for (Path file : created) Files.deleteIfExists(file);
            Files.delete(date); Files.delete(root);
        }
        System.out.println("AeroDataBoxRawMongoImporterTest: passed (offline)");
        if (args.length > 0) {
            Map<String, Document> saved = new HashMap<>();
            var files = AeroDataBoxRawMongoImporter.findFiles(Path.of(args[0]));
            var result = AeroDataBoxRawMongoImporter.importFiles(files, d -> saved.put(d.getString("batch_key"), d) == null);
            check(result.failures().isEmpty(), "Local dataset parses: " + result.failures());
            System.out.println("Local files validated without MongoDB: " + result.filesFound() + "; unique batches: " + saved.size());
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
