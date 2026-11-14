package avia.storage;

import avia.model.FlightHistoricalStats;
import avia.service.MongoOfferEnrichmentMapper;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.*;

/** Only enriched_flight_offers is writable; both source collections are read-only. */
public final class MongoFlightEnrichmentStorage implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;
    public MongoFlightEnrichmentStorage() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGO_URI is required");
        client = MongoClients.create(uri);
        database = client.getDatabase("avia");
    }
    public List<Document> readOffers() {
        return database.getCollection("raw_prices_for_dates").find().into(new ArrayList<>());
    }
    public Map<String, FlightHistoricalStats> readHistory() {
        Map<String, FlightHistoricalStats> result = new HashMap<>();
        try (var cursor = database.getCollection("flight_statistics").find().iterator()) {
            while (cursor.hasNext()) {
                var stats = MongoOfferEnrichmentMapper.readStats(cursor.next());
                if (result.putIfAbsent(stats.flightServiceKey(), stats) != null)
                    throw new IllegalStateException("Duplicate statistics key");
            }
        }
        return result;
    }
    public MongoBulkUpserts.Result saveAll(List<Document> documents) {
        var target = database.getCollection("enriched_flight_offers");
        target.createIndex(Indexes.ascending("sourceOfferId"), new IndexOptions().unique(true));
        target.createIndex(Indexes.ascending("flightServiceKey"));
        target.createIndex(Indexes.ascending("matchStatus"));
        return MongoBulkUpserts.save(target, documents, "sourceOfferId", d -> d);
    }
    public long count() { return database.getCollection("enriched_flight_offers").countDocuments(); }
    @Override public void close() { client.close(); }
}
