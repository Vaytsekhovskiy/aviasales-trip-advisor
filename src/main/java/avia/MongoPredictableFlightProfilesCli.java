package avia;
import avia.model.*;
import avia.service.*;
import avia.storage.*;
import com.mongodb.client.MongoClients;
import java.util.*;
import java.util.function.ToLongFunction;

public final class MongoPredictableFlightProfilesCli {
    public static void main(String[] args) {
        try {
            boolean write = args.length > 0 && args[0].equals("write");
            long minDelay = 0, minPrice = 0;
            if (write) {
                if (args.length != 5) throw new IllegalArgumentException();
                Map<String, Long> options = new HashMap<>();
                for (int i = 1; i < args.length; i += 2)
                    if (options.put(args[i], Long.parseLong(args[i+1])) != null) throw new IllegalArgumentException();
                if (!options.keySet().equals(Set.of("--min-delay-flights", "--min-price-flights"))) throw new IllegalArgumentException();
                minDelay = options.get("--min-delay-flights"); minPrice = options.get("--min-price-flights");
                if (minDelay < 1 || minPrice < 1) throw new IllegalArgumentException();
            } else if (args.length != 1 || !args[0].equals("analyze")) throw new IllegalArgumentException();
            String uri = System.getenv("MONGO_URI");
            if (uri == null || uri.isBlank()) throw new IllegalArgumentException();
            try (var client = MongoClients.create(uri); var delayReader = new MongoFlightReader()) {
                var db = client.getDatabase("avia");
                var aggregator = new FlightProfileAggregator();
                var delay = aggregator.delayCounts(delayReader.readAll());
                var price = aggregator.priceCounts(new MongoProfilePriceReader().readAll(db));
                var common = new PredictableFlightProfileService().intersect(delay, price);
                System.out.printf("Delay profiles: %d%nPrice profiles: %d%nCommon profiles: %d%n", delay.size(), price.size(), common.size());
                for (int n : new int[]{1,3,5,10}) System.out.printf("N >= %d: %d%n", n, common.stream().filter(p -> p.passes(n,n)).count());
                summary("delayHistoryFlights", common, PredictableFlightProfile::delayHistoryFlights);
                summary("priceHistoryFlights", common, PredictableFlightProfile::priceHistoryFlights);
                System.out.println("Top profiles (descending delay + price, ties by key):");
                common.stream().sorted(Comparator.<PredictableFlightProfile>comparingLong(p -> p.delayHistoryFlights()+p.priceHistoryFlights()).reversed()
                        .thenComparing(PredictableFlightProfile::profileKey)).limit(20)
                        .forEach(p -> System.out.printf("%s delayN=%d priceN=%d%n", p.profileKey(), p.delayHistoryFlights(), p.priceHistoryFlights()));
                if (write) {
                    long d = minDelay, p = minPrice;
                    var selected = common.stream().filter(f -> f.passes(d,p)).toList();
                    new MongoPredictableFlightProfileStorage().rebuild(db, selected);
                    System.out.println("Written profiles: " + selected.size());
                }
            }
        } catch (Exception e) {
            System.err.println("Profiles pipeline failed (" + e.getClass().getSimpleName() + "). MONGO_URI required. Usage: analyze | write --min-delay-flights N --min-price-flights N (positive integers).");
            System.exit(1);
        }
    }
    private static void summary(String label, List<PredictableFlightProfile> profiles, ToLongFunction<PredictableFlightProfile> count) {
        long[] values = profiles.stream().mapToLong(count).sorted().toArray();
        if (values.length == 0) { System.out.println(label + ": min/median/max = n/a"); return; }
        int n = values.length;
        double median = values[(n-1)/2]/2.0 + values[n/2]/2.0;
        System.out.printf(Locale.ROOT, "%s: min=%d median=%.1f max=%d%n", label, values[0], median, values[n-1]);
    }
}
