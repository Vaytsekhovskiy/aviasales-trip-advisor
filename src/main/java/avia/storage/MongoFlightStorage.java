package avia.storage;

import avia.model.FlightRecord;
import avia.service.MongoRawFlightNormalizer;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

/** Writer for globally deduplicated flights, not batch-existence based FlightStorage. */
public final class MongoFlightStorage implements AutoCloseable {
    public static final String DATABASE = "avia", SOURCE = "raw_aerodatabox", TARGET = "flights";
    private final MongoClient client;
    private final MongoCollection<Document> flights;

    public MongoFlightStorage() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGO_URI is required");
        client = MongoClients.create(uri);
        try {
            flights = client.getDatabase(DATABASE).getCollection(TARGET);
            flights.createIndex(ascending("flightId"), new IndexOptions().unique(true)
                    .partialFilterExpression(new Document("flightId", new Document("$type", "string"))));
            for (String field : List.of("flightServiceKey", "originIata", "destinationIata", "airlineIata", "scheduledDepartureUtc")) {
                flights.createIndex(ascending(field));
            }
        } catch (RuntimeException e) { client.close(); throw e; }
    }

    /** Caller closes the cursor; source collection is read-only. */
    public MongoCursor<Document> rawBatches() {
        return client.getDatabase(DATABASE).getCollection(SOURCE).find().sort(ascending("batch_key")).iterator();
    }

    public boolean save(MongoRawFlightNormalizer.Flight flight) {
        Document document = toDocument(flight);
        return flights.replaceOne(eq("flightId", flight.record().flightId()), document,
                new ReplaceOptions().upsert(true)).getUpsertedId() != null;
    }

    public MongoBulkUpserts.Result saveAll(List<MongoRawFlightNormalizer.Flight> records) {
        return MongoBulkUpserts.save(flights, records, "flightId", MongoFlightStorage::toDocument);
    }

    /** Preserves all FlightRecord components, with UTC instants as BSON dates and local times as ISO strings. */
    public static Document toDocument(MongoRawFlightNormalizer.Flight flight) {
        FlightRecord record = flight.record();
        if (record.flightId() == null || record.flightId().isBlank()) throw new IllegalArgumentException("Stable flightId required");
        Document result = new Document();
        try {
            for (var component : FlightRecord.class.getRecordComponents()) {
                Object value = component.getAccessor().invoke(record);
                if (value instanceof Instant instant) value = Date.from(instant);
                else if (value instanceof LocalDateTime || value instanceof DayOfWeek) value = value.toString();
                result.put(component.getName(), value);
            }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("FlightRecord mapping failed", e); }
        String[] key = {record.airlineIata(), record.flightNumber(), record.originIata(), record.destinationIata()};
        result.put("flightServiceKey", Arrays.stream(key).anyMatch(v -> v == null || v.isBlank() || v.contains("|"))
                ? null : String.join("|", key));
        result.put("sourceBatchKeys", flight.sourceBatchKeys());
        return result;
    }

    public long count() { return flights.countDocuments(); }
    public List<Document> findService(String key) {
        return flights.find(eq("flightServiceKey", key)).sort(ascending("scheduledDepartureUtc"))
                .projection(Projections.include("flightId", "flightServiceKey", "scheduledDepartureUtc",
                        "departureDelayMin", "arrivalDelayMin", "sourceBatchKeys")).into(new ArrayList<>());
    }
    @Override public void close() { client.close(); }
}
