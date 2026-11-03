package avia.storage;

import avia.model.*;
import avia.service.MongoOfferEnrichmentMapper;
import com.mongodb.client.*;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import java.time.*;
import java.util.*;

/** Only reads enriched_flight_offers. Does not create indexes or access APIs. */
public final class MongoEnrichedOfferReader implements AutoCloseable {
    private final MongoClient client;
    public MongoEnrichedOfferReader() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGO_URI is required");
        client = MongoClients.create(uri);
    }
    public OfferDashboardData read() {
        List<OfferDashboardData.Row> rows = new ArrayList<>();
        try (var cursor = client.getDatabase("avia").getCollection("enriched_flight_offers").find()
                .projection(Projections.include("sourceOfferId", "matchStatus", "flightServiceKey", "offer",
                        "historicalStats", "_collected_at", "enrichedAt", "validationError")).iterator()) {
            while (cursor.hasNext()) rows.add(toRow(cursor.next()));
        }
        return new OfferDashboardData(Instant.now(), rows);
    }
    public static OfferDashboardData.Row toRow(Document doc) {
        String id = doc.getString("sourceOfferId");
        String status = doc.getString("matchStatus");
        Instant collected = instant(doc.get("_collected_at")), enriched = instant(doc.get("enrichedAt"));
        if ("INVALID_OFFER".equals(status))
            return new OfferDashboardData.Row(id, status, null, collected, enriched, doc.getString("validationError"));
        if (!Set.of("MATCHED", "NOT_MATCHED").contains(status == null ? "" : status))
            throw new IllegalArgumentException("Unknown match status");
        Document offer = doc.get("offer", Document.class);
        var normalized = new AviasalesFlightOffer(offer.getString("airlineIata"), offer.getString("flightNumber"),
                offer.getString("originAirport"), offer.getString("destinationAirport"),
                OffsetDateTime.parse(offer.getString("departureAt")), integer(offer, "price"),
                offer.getString("currency"), Math.toIntExact(integer(offer, "transfers")),
                Math.toIntExact(integer(offer, "durationMin")));
        Document history = doc.get("historicalStats", Document.class);
        var result = new EnrichedFlightOffer(normalized, history == null ? null : MongoOfferEnrichmentMapper.readStats(history));
        if (!result.matchStatus().name().equals(status) || !result.flightServiceKey().equals(doc.getString("flightServiceKey")))
            throw new IllegalArgumentException("Inconsistent enriched document");
        return new OfferDashboardData.Row(id, status, result, collected, enriched, null);
    }
    private static long integer(Document d, String field) {
        return new java.math.BigDecimal(d.get(field).toString()).longValueExact();
    }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Date date) return date.toInstant();
        if (value instanceof String text) return Instant.parse(text);
        throw new IllegalArgumentException("Invalid timestamp");
    }
    @Override public void close() { client.close(); }
}
