package avia.storage;

import avia.model.FlightRecord;
import com.mongodb.client.*;
import org.bson.Document;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Read-only whole-collection reader; FlightReader's batch contract does not apply after deduplication. */
public final class MongoFlightReader implements AutoCloseable {
    private final MongoClient client;

    public MongoFlightReader() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGO_URI is required");
        client = MongoClients.create(uri);
    }

    /** Abort on malformed documents: partial input must not overwrite complete statistics. */
    public List<FlightRecord> readAll() {
        List<FlightRecord> records = new ArrayList<>();
        try (var cursor = client.getDatabase("avia").getCollection("flights").find().iterator()) {
            while (cursor.hasNext()) records.add(fromDocument(cursor.next()));
        }
        return List.copyOf(records);
    }

    public static FlightRecord fromDocument(Document document) {
        var components = FlightRecord.class.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            values[i] = convert(document.get(component.getName()), component.getType());
        }
        try {
            FlightRecord record = FlightRecord.class.getDeclaredConstructor(Arrays.stream(components)
                    .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(values);
            if (record.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported FlightRecord schema");
            return record;
        } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Invalid FlightRecord document", e); }
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            if (type.isPrimitive()) throw new IllegalArgumentException("Missing required primitive field");
            return null;
        }
        if (type == Instant.class && value instanceof Date date) return date.toInstant();
        if (type == LocalDateTime.class && value instanceof String text) return LocalDateTime.parse(text);
        if (type == DayOfWeek.class && value instanceof String text) return DayOfWeek.valueOf(text);
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            if (type == Long.class) return decimal.longValueExact();
            if (type == Integer.class || type == int.class) return decimal.intValueExact();
            if (type == Double.class) return decimal.doubleValue();
        }
        if ((type == boolean.class && value instanceof Boolean) || type.isInstance(value)) return value;
        throw new IllegalArgumentException("Unexpected BSON field type for " + type.getSimpleName());
    }

    @Override public void close() { client.close(); }
}
