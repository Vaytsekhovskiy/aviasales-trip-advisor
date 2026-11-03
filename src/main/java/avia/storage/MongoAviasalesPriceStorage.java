package avia.storage;

import avia.service.AviasalesOfferAudit;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

/** Append-only price observations. Legacy documents without snapshot_id remain untouched. */
public final class MongoAviasalesPriceStorage implements AutoCloseable {
    private final MongoClient client;
    private final MongoCollection<Document> offers;
    public MongoAviasalesPriceStorage() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("MONGO_URI required");
        client = MongoClients.create(uri);
        try {
            offers = client.getDatabase("avia").getCollection("raw_prices_for_dates");
            offers.createIndex(ascending("snapshot_id"), new IndexOptions().unique(true)
                    .partialFilterExpression(new Document("snapshot_id", new Document("$type", "string"))));
        } catch (RuntimeException e) { client.close(); throw e; }
    }
    /** Return number of new snapshots; matched snapshots are left byte-for-byte unchanged. */
    public int save(List<Document> documents) {
        if (documents.isEmpty()) return 0;
        Map<String, Document> unique = new LinkedHashMap<>();
        for (Document doc : documents) unique.put(doc.getString("snapshot_id"), doc);
        List<WriteModel<Document>> writes = new ArrayList<>();
        for (var entry : unique.entrySet()) writes.add(new UpdateOneModel<>(eq("snapshot_id", entry.getKey()),
                new Document("$setOnInsert", entry.getValue()), new UpdateOptions().upsert(true)));
        return offers.bulkWrite(writes, new BulkWriteOptions().ordered(false)).getUpserts().size();
    }
    public AviasalesOfferAudit.Result audit() {
        Set<String> history = new HashSet<>();
        try (var cursor = client.getDatabase("avia").getCollection("flight_statistics").find()
                .projection(Projections.include("flightServiceKey")).iterator()) {
            while (cursor.hasNext()) {
                Object key = cursor.next().get("flightServiceKey");
                if (key instanceof String s) history.add(s);
            }
        }
        try (var cursor = offers.find().projection(Projections.include("airline", "flight_number", "origin_airport", "destination_airport")).iterator()) {
            return new AviasalesOfferAudit().audit(() -> cursor, history);
        }
    }
    @Override public void close() { client.close(); }
}
