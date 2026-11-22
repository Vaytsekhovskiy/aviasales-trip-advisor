package avia.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

/** Independent raw-batch storage; never accesses Aviasales collections. */
public final class MongoAeroDataBoxRawStorage implements AutoCloseable {
    public static final String DATABASE = "avia";
    public static final String COLLECTION = "raw_aerodatabox";
    private static final Pattern FILE_NAME = Pattern.compile("([A-Za-z0-9]{3,4})_(\\d{2})-(\\d{2})\\.json");
    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoAeroDataBoxRawStorage() {
        this(false);
    }

    /** Read-only mode does not create indexes or write any documents (dry-run). */
    public MongoAeroDataBoxRawStorage(boolean readOnly) {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) throw new IllegalStateException("MONGO_URI is required");
        client = MongoClients.create(uri);
        try {
            collection = client.getDatabase(DATABASE).getCollection(COLLECTION);
            if (!readOnly) collection.createIndex(ascending("batch_key"), new IndexOptions().unique(true));
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
    }

    public record Batch(String airport, LocalDate date, int fromHour, int toHour) {
        public String batchKey() {
            return String.format(Locale.ROOT, "%s|%s|%02d|%02d", airport, date, fromHour, toHour);
        }
    }

    /** Parses the immediate date directory and file name, without reading the file. */
    public static Batch parsePath(Path path) {
        if (path.getParent() == null || path.getFileName() == null) throw new IllegalArgumentException("Missing date directory");
        var matcher = FILE_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) throw new IllegalArgumentException("Expected AIRPORT_HH-HH.json");
        LocalDate date = LocalDate.parse(path.getParent().getFileName().toString());
        int from = Integer.parseInt(matcher.group(2)), to = Integer.parseInt(matcher.group(3));
        if (from > 23 || to > 24 || to <= from || to - from > 12) {
            throw new IllegalArgumentException("Expected a 1-12 hour window within a day");
        }
        return new Batch(matcher.group(1).toUpperCase(Locale.ROOT), date, from, to);
    }

    /** Payload remains a nested BSON document, including its original arrays and fields. */
    public static Document toDocument(Path file, String json, Instant ingestedAt) {
        Batch batch = parsePath(file);
        Document payload = Document.parse(json);
        return new Document("source", "AERODATABOX").append("endpoint", "FIDS")
                .append("batch_key", batch.batchKey()).append("airport", batch.airport())
                .append("date", batch.date().toString()).append("from_hour", batch.fromHour())
                .append("to_hour", batch.toHour()).append("ingested_at", Date.from(ingestedAt))
                .append("payload", payload);
    }

    /** Returns true for insertion, false for replacement of an existing batch. */
    public boolean save(Document document) {
        var result = collection.replaceOne(eq("batch_key", document.getString("batch_key")), document,
                new ReplaceOptions().upsert(true));
        return result.getUpsertedId() != null;
    }

    @Override public void close() { client.close(); }

    public boolean exists(String batchKey) {
        return collection.find(eq("batch_key", batchKey)).projection(new Document("_id", 1)).first() != null;
    }

    /** Never replace an existing raw batch, including a concurrent insertion. */
    public boolean insertIfAbsent(Document document) {
        return collection.updateOne(eq("batch_key", document.getString("batch_key")),
                new Document("$setOnInsert", document), new com.mongodb.client.model.UpdateOptions().upsert(true))
                .getUpsertedId() != null;
    }
}
