package avia;

import avia.service.*;
import avia.storage.MongoAviasalesPriceStorage;
import java.time.*;
import java.util.*;

public final class AviasalesPricesBulkCollectorCli {
    public record Options(YearMonth month, List<String> airports, boolean dryRun, int maxRequests) { }
    public static Options parse(String[] args) {
        Map<String, String> values = new HashMap<>(); boolean dryRun = false;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.equals("--dry-run")) { dryRun = true; continue; }
            if (!Set.of("--month", "--airports", "--max-requests").contains(name) || i + 1 == args.length
                    || values.containsKey(name)) throw new IllegalArgumentException("Invalid CLI arguments");
            values.put(name, args[++i]);
        }
        if (!values.containsKey("--month")) throw new IllegalArgumentException("--month YYYY-MM required");
        List<String> airports = Arrays.stream(values.getOrDefault("--airports", "SVO,LED,AER,OVB,SVX").split(",", -1))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).toList();
        AviasalesPricesBulkCollector.routes(airports);
        int max = Integer.parseInt(values.getOrDefault("--max-requests", "2147483647"));
        if (max < 1) throw new IllegalArgumentException("--max-requests must be positive");
        return new Options(YearMonth.parse(values.get("--month")), airports, dryRun, max);
    }
    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var routes = AviasalesPricesBulkCollector.routes(options.airports());
            System.out.println("Month: " + options.month() + "\nAirports: " + String.join(",", options.airports()));
            System.out.println("Routes: " + routes.size() + "\nAPI requests planned: " + Math.min(routes.size(), options.maxRequests()));
            if (options.dryRun()) {
                routes.forEach(r -> System.out.println(r.origin() + " → " + r.destination()));
                System.out.println("Dry-run: no API or Mongo access. Retry attempts also consume --max-requests.");
                return;
            }
            String token = System.getenv("AVIASALES_TOKEN");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("AVIASALES_TOKEN required");
            var client = new AviasalesClient(token);
            System.out.println("MongoDB: connecting / checking snapshot_id index in avia.raw_prices_for_dates...");
            long mongoStarted = System.nanoTime();
            try (var storage = new MongoAviasalesPriceStorage()) {
                System.out.printf(Locale.ROOT, "MongoDB: ready (%.2fs)%n", (System.nanoTime() - mongoStarted) / 1e9);
                var result = new AviasalesPricesBulkCollector().run(routes, options.month(), options.maxRequests(),
                        route -> client.fetchPricesForDates(route.origin(), route.destination(), options.month()),
                        storage::save, Thread::sleep, Clock.systemUTC(), System.out::println);
                System.out.println("\nAviasales prices_for_dates collection completed\nMonth: " + options.month());
                System.out.println("Routes planned: " + routes.size() + "\nRequests completed: " + result.completed()
                        + "\nAPI attempts (including retry): " + result.attempts() + "\nEmpty responses: " + result.empty()
                        + "\nOffers received: " + result.received() + "\nOffers saved: " + result.saved()
                        + "\nFailed requests: " + result.failures().size() + "\nUnconfirmed offers: " + result.uncertainOffers());
                System.out.println("Database: avia\nCollection: raw_prices_for_dates");
                result.failures().forEach(System.err::println);
                System.out.println("Audit: reading raw_prices_for_dates and flight_statistics from MongoDB...");
                long auditStarted = System.nanoTime();
                printAudit(storage.audit());
                System.out.printf(Locale.ROOT, "Audit completed (%.2fs)%n", (System.nanoTime() - auditStarted) / 1e9);
                if (!result.failures().isEmpty()) throw new IllegalStateException("Some requests or writes failed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); System.err.println("Collection interrupted"); System.exit(1);
        } catch (Exception e) {
            System.err.println("Collection/audit failed (" + e.getClass().getSimpleName()
                    + "). Check CLI arguments, AVIASALES_TOKEN, MONGO_URI, network and access. Credentials are not logged.");
            System.exit(1);
        }
    }
    public static void printAudit(AviasalesOfferAudit.Result audit) {
        System.out.println("\nAudit: entire raw_prices_for_dates collection, including older snapshots");
        System.out.println("Total offers: " + audit.total() + "\nOffers with airline: " + audit.airline()
                + "\nOffers with flight_number: " + audit.number() + "\nOffers with origin_airport + destination_airport: " + audit.airports()
                + "\nOffers where flightServiceKey can be built: " + audit.validKeys()
                + "\nMatched offers: " + audit.matched() + "\nNot matched offers: " + audit.notMatched()
                + "\nMatch rate (all offers): " + percent(audit.matchRate())
                + "\nMatch rate (valid keys only): " + percent(audit.validKeyMatchRate()));
        System.out.println("Matched flightServiceKey examples (up to 5 distinct): " + audit.examples());
    }
    private static String percent(Double value) { return value == null ? "N/A" : String.format(Locale.ROOT, "%.2f %%", value); }
}
