package avia;

import avia.renderer.OfferDashboardHtmlRenderer;
import avia.storage.MongoEnrichedOfferReader;
import java.nio.file.Path;

/** Generates a standalone report; never writes to MongoDB. */
public final class MongoOfferDashboardCli {
    public static void main(String[] args) {
        try {
            if (args.length > 1) throw new IllegalArgumentException("Expected optional output HTML path");
            Path output = Path.of(args.length == 0 ? "output/offer-dashboard.html" : args[0]);
            try (var reader = new MongoEnrichedOfferReader()) {
                System.out.println("Reading avia.enriched_flight_offers...");
                var data = reader.read();
                System.out.println("Snapshots read: " + data.rows().size());
                for (String status : new String[]{"MATCHED", "NOT_MATCHED", "INVALID_OFFER"})
                    System.out.println(status + ": " + data.rows().stream().filter(r -> status.equals(r.status())).count());
                new OfferDashboardHtmlRenderer().render(output, data);
                System.out.println("Dashboard created: " + output.toAbsolutePath());
                System.out.println("MongoDB read only. No API requests. Open the HTML in a browser.");
            }
        } catch (Exception e) {
            System.err.println("Offer dashboard failed (" + e.getClass().getSimpleName()
                    + "). Check MONGO_URI, source document schema, resources and output permissions.");
            System.exit(1);
        }
    }
}
