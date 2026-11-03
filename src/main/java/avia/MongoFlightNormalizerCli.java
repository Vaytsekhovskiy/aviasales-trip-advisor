package avia;

import avia.service.MongoRawFlightNormalizer;
import avia.storage.MongoFlightStorage;
import java.util.*;

/** Mongo raw -> existing preprocessing -> global deduplication -> Mongo flights. */
public final class MongoFlightNormalizerCli {
    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("Usage: avia.MongoFlightNormalizerCli (no arguments)");
            System.exit(1);
        }
        if (System.getenv("MONGO_URI") == null || System.getenv("MONGO_URI").isBlank()) {
            System.err.println("MONGO_URI is required in this Run Configuration / process environment.");
            System.exit(1);
        }
        try (var storage = new MongoFlightStorage()) {
            MongoRawFlightNormalizer.Result result;
            try (var cursor = storage.rawBatches()) {
                result = new MongoRawFlightNormalizer().normalize(() -> cursor);
            }
            // An incomplete raw scan must not overwrite previously good rows with partial winners.
            if (!result.failures().isEmpty()) {
                printSummary(result, 0, 0, result.failures());
                System.err.println("No flights written: fix failed raw batches and rerun.");
                throw new IllegalStateException("Raw normalization incomplete");
            }
            var writes = storage.saveAll(result.flights());
            var failures = writes.failures();
            printSummary(result, writes.inserted(), writes.updated(), failures);
            System.out.println("Unconfirmed documents: " + writes.uncertain());
            System.out.println("Documents in flights: " + storage.count());
            var example = storage.findService("DP|6525|SVO|OVB");
            System.out.println("DP|6525|SVO|OVB: " + example.size());
            example.forEach(d -> System.out.println(d.toJson()));
            if (!failures.isEmpty()) throw new IllegalStateException("Some flights failed");
        } catch (Exception e) {
            // Avoid exposing URI/credentials from driver exception messages.
            System.err.println("Normalization failed (" + e.getClass().getSimpleName()
                    + "). Check raw metadata, MONGO_URI, Atlas access and collection permissions.");
            System.exit(1);
        }
    }

    private static void printSummary(MongoRawFlightNormalizer.Result r, int inserted, int updated, List<String> failures) {
        System.out.println("MongoDB flight normalization\n");
        System.out.println("Raw batches read: " + r.batches());
        System.out.println("Flight observations parsed: " + r.observations());
        System.out.println("Unique stable flights: " + r.flights().size());
        System.out.println("Duplicate observations removed: " + r.duplicates());
        System.out.println("Skipped without stable flightId: " + r.missingIds());
        System.out.println("Batches without collection time: " + r.batchesWithoutCollectionTime());
        System.out.println("Inserted: " + inserted + "\nUpdated: " + updated + "\nErrors: " + failures.size());
        System.out.println("\nDatabase: avia\nSource: raw_aerodatabox\nTarget: flights");
        failures.forEach(System.err::println);
    }
}
