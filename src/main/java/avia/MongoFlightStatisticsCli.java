package avia;

import avia.service.FlightHistoryAggregator;
import avia.storage.*;
import java.time.Instant;
import java.util.*;

public final class MongoFlightStatisticsCli {
    public static void main(String[] args) {
        if (args.length != 0 || System.getenv("MONGO_URI") == null || System.getenv("MONGO_URI").isBlank()) {
            System.err.println("Run avia.MongoFlightStatisticsCli without arguments; MONGO_URI is required.");
            System.exit(1);
        }
        try (var reader = new MongoFlightReader()) {
            var flights = reader.readAll();
            var stats = new FlightHistoryAggregator().aggregate(flights);
            Instant computedAt = Instant.now();
            try (var storage = new MongoFlightStatisticsStorage()) {
                var writes = storage.saveAll(stats.values(), computedAt);
                var failures = writes.failures();
                System.out.println("MongoDB flight historical statistics\n");
                System.out.println("Flights read: " + flights.size());
                System.out.println("Flight services calculated: " + stats.size());
                System.out.println("Inserted: " + writes.inserted() + "\nUpdated: " + writes.updated()
                        + "\nWrite errors: " + failures.size() + "\nUnconfirmed documents: " + writes.uncertain());
                System.out.println("\nDatabase: avia\nSource: flights\nTarget: flight_statistics");
                System.out.println("Documents in flight_statistics: " + storage.count());
                var example = storage.find("DP|6525|SVO|OVB");
                System.out.println("DP|6525|SVO|OVB: " + (example == null ? "not found" : example.toJson()));
                failures.forEach(System.err::println);
                if (!failures.isEmpty()) throw new IllegalStateException("Some statistics writes failed");
            }
        } catch (Exception e) {
            // Driver messages may include connection details; never print URI or credentials.
            System.err.println("Statistics pipeline failed (" + e.getClass().getSimpleName()
                    + "). Check MONGO_URI, Atlas access, document types and permissions.");
            System.exit(1);
        }
    }
}
