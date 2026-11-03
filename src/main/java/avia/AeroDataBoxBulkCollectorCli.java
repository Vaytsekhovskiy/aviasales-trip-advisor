package avia;

import avia.client.AeroDataBoxClient;
import avia.service.*;
import avia.storage.MongoAeroDataBoxRawStorage;
import org.bson.Document;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class AeroDataBoxBulkCollectorCli {
    public record Options(LocalDate from, LocalDate to, List<String> airports, boolean dryRun, int maxBatches) { }
    public static Options parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        boolean dry = false;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.equals("--dry-run")) { dry = true; continue; }
            if (!Set.of("--from", "--to", "--airports", "--max-batches").contains(name)
                    || i + 1 == args.length || values.containsKey(name)) throw new IllegalArgumentException("Invalid CLI options");
            values.put(name, args[++i]);
        }
        if (!values.containsKey("--from") || !values.containsKey("--to")) throw new IllegalArgumentException("--from and --to required");
        var airports = Arrays.stream(values.getOrDefault("--airports", "SVO,LED,AER,OVB,SVX").split(",", -1))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
        var options = new Options(LocalDate.parse(values.get("--from")), LocalDate.parse(values.get("--to")),
                airports, dry, Integer.parseInt(values.getOrDefault("--max-batches", "2147483647")));
        if (options.maxBatches() < 1) throw new IllegalArgumentException("--max-batches must be positive");
        FlightDatasetPlan.batches(options.from(), options.to(), airports); // Validate before opening Mongo.
        return options;
    }
    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var batches = FlightDatasetPlan.batches(options.from(), options.to(), options.airports());
            AeroDataBoxClient api = options.dryRun() ? null : new AeroDataBoxClient(System.getenv("AERODATABOX_API_KEY"));
            try (var mongo = new MongoAeroDataBoxRawStorage(options.dryRun())) {
                var store = new AeroDataBoxBulkCollector.Store() {
                    public boolean exists(String key) { return mongo.exists(key); }
                    public boolean insertIfAbsent(Document doc) { return mongo.insertIfAbsent(doc); }
                };
                var result = new AeroDataBoxBulkCollector().run(batches, options.dryRun(), options.maxBatches(), store,
                        b -> api.fetchFlights(b), Thread::sleep, (batch, json) -> {
                            Path path = Path.of("data/raw").resolve(AeroDataBoxBulkCollector.relativePath(batch));
                            Files.createDirectories(path.getParent());
                            Files.writeString(path, json, StandardOpenOption.CREATE_NEW);
                        }, Clock.systemUTC(), System.out::println);
                System.out.println(options.dryRun() ? "\nAeroDataBox collection dry-run" : "\nAeroDataBox collection completed");
                System.out.println("Planned: " + result.planned() + "\nAlready existed: " + result.existed());
                System.out.println((options.dryRun() ? "Would request: " : "Requested: ") + result.requested());
                System.out.println("API attempts (including retry): " + result.attempts() + "\nSaved: " + result.saved()
                        + "\nFailed: " + result.failures().size() + "\nDeferred: " + result.deferred());
                System.out.println("Date range: " + options.from() + ".." + options.to() + "\nAirports: " + options.airports()
                        + "\nDatabase: avia\nCollection: raw_aerodatabox");
                result.failures().forEach(f -> System.err.println("Failed batch: " + f));
                result.backupWarnings().forEach(f -> System.err.println("Backup warning: " + f));
                if (!result.failures().isEmpty()) throw new IllegalStateException("Batches failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); System.err.println("Collection interrupted; rerun to resume from Mongo"); System.exit(1);
        } catch (Exception e) {
            System.err.println("Collection failed (" + e.getClass().getSimpleName()
                    + "). Check --from/--to/--airports/--max-batches, MONGO_URI and AERODATABOX_API_KEY.");
            System.exit(1);
        }
    }
}
