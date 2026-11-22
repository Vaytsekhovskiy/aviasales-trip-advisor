package avia.storage;

import avia.model.FlightHistoricalStats;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.time.Instant;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

/** Writes only avia.flight_statistics; values are stored without rounding. */
public final class MongoFlightStatisticsStorage implements AutoCloseable {
    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoFlightStatisticsStorage() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGO_URI is required");
        client = MongoClients.create(uri);
        try {
            collection = client.getDatabase("avia").getCollection("flight_statistics");
            collection.createIndex(ascending("flightServiceKey"), new IndexOptions().unique(true));
            for (String field : List.of("airlineIata", "originIata", "destinationIata")) collection.createIndex(ascending(field));
        } catch (RuntimeException e) { client.close(); throw e; }
    }

    public boolean save(FlightHistoricalStats stats, Instant computedAt) {
        return collection.replaceOne(eq("flightServiceKey", stats.flightServiceKey()), toDocument(stats, computedAt),
                new ReplaceOptions().upsert(true)).getUpsertedId() != null;
    }

    public MongoBulkUpserts.Result saveAll(Collection<FlightHistoricalStats> stats, Instant computedAt) {
        return MongoBulkUpserts.save(collection, stats, "flightServiceKey", s -> toDocument(s, computedAt));
    }

    public static Document toDocument(FlightHistoricalStats stats, Instant computedAt) {
        String[] parts = stats.flightServiceKey().split("\\|", -1);
        if (parts.length != 4 || Arrays.stream(parts).anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid service key");
        return new Document("flightServiceKey", stats.flightServiceKey())
                .append("airlineIata", parts[0]).append("flightNumber", parts[1])
                .append("originIata", parts[2]).append("destinationIata", parts[3])
                .append("sampleSize", stats.sampleSize())
                .append("medianDepartureDelayMin", stats.medianDepartureDelayMin())
                .append("meanDepartureDelayMin", stats.meanDepartureDelayMin())
                .append("medianArrivalDelayMin", stats.medianArrivalDelayMin())
                .append("meanArrivalDelayMin", stats.meanArrivalDelayMin())
                .append("departureDelay15Rate", stats.departureDelay15Rate())
                .append("departureDelay30Rate", stats.departureDelay30Rate())
                .append("departureDelay60Rate", stats.departureDelay60Rate())
                .append("cancellationRate", stats.cancellationRate())
                .append("medianDelayRecoveryMin", stats.medianDelayRecoveryMin())
                .append("computedAt", Date.from(computedAt));
    }

    public long count() { return collection.countDocuments(); }
    public Document find(String key) { return collection.find(eq("flightServiceKey", key)).first(); }
    @Override public void close() { client.close(); }
}
