package avia;

import avia.service.MongoOfferEnrichmentMapper;
import avia.storage.MongoFlightEnrichmentStorage;
import org.bson.Document;
import java.time.Instant;
import java.util.*;

public final class MongoFlightEnrichmentCli {
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
            try (var storage = new MongoFlightEnrichmentStorage()) {
                System.out.println("Reading avia.raw_prices_for_dates...");
                var raw = storage.readOffers();
                System.out.println("Offers read: " + raw.size() + "\nReading avia.flight_statistics...");
                var history = storage.readHistory();
                System.out.println("Historical services read: " + history.size());
                var mapper = new MongoOfferEnrichmentMapper();
                Instant now = Instant.now();
                List<Document> documents = raw.stream().map(d -> mapper.map(d, history, now)).toList();
                for (String status : List.of("MATCHED", "NOT_MATCHED", "INVALID_OFFER")) {
                    System.out.println(status + ": " + documents.stream().filter(d -> status.equals(d.getString("matchStatus"))).count());
                }
                documents.stream().filter(d -> "INVALID_OFFER".equals(d.getString("matchStatus")))
                        .forEach(d -> System.out.println("Invalid source " + d.getString("sourceOfferId") + ": " + d.getString("validationError")));
                System.out.println("Writing avia.enriched_flight_offers (bulk upsert by sourceOfferId)...");
                var result = storage.saveAll(documents);
                System.out.println("Inserted: " + result.inserted() + "\nUpdated: " + result.updated()
                        + "\nWrite errors: " + result.failures().size() + "\nUnconfirmed documents: " + result.uncertain());
                result.failures().forEach(System.err::println);
                System.out.println("Documents in enriched_flight_offers: " + storage.count());
                System.out.println("Source collections were read only. Match uses flightServiceKey only.");
                if (!result.failures().isEmpty() || result.uncertain() > 0)
                    throw new IllegalStateException("Incomplete writes");
            }
        } catch (Exception e) {
            System.err.println("Enrichment failed (" + e.getClass().getSimpleName()
                    + "). Check MONGO_URI, connectivity, permissions and source field types. Credentials are not logged.");
            System.exit(1);
        }
    }
}
