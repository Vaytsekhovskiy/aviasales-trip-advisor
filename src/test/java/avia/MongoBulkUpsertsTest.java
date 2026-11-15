package avia;

import avia.storage.MongoBulkUpserts;
import com.mongodb.*;
import com.mongodb.bulk.*;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;

/** Offline driver-boundary tests: no MongoClient or network. */
public final class MongoBulkUpsertsTest {
    public static void main(String[] args) {
        for (String keyField : List.of("flightId", "flightServiceKey")) {
            Map<String, Document> database = new HashMap<>();
            List<Integer> calls = new ArrayList<>();
            var collection = fake(operations -> {
                calls.add(operations.size());
                List<BulkWriteUpsert> inserted = new ArrayList<>();
                int matched = 0;
                for (int i = 0; i < operations.size(); i++) {
                    var operation = operations.get(i);
                    check(operation.getReplaceOptions().isUpsert(), "Upsert retained");
                    Document doc = operation.getReplacement();
                    String key = doc.getString(keyField);
                    var filter = operation.getFilter().toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry());
                    check(filter.getString(keyField).getValue().equals(key), "Filter uses correct identity");
                    if (database.put(key, doc) == null) inserted.add(new BulkWriteUpsert(i, new BsonString(key)));
                    else matched++;
                }
                // Modified count intentionally zero: Updated must count matched, including unchanged.
                return result(matched, inserted);
            });
            List<Document> docs = new ArrayList<>();
            for (int i = 0; i < 10001; i++) docs.add(new Document(keyField, "key-" + i).append("sampleSize", 7));
            var first = MongoBulkUpserts.save(collection, docs, keyField, Function.identity());
            check(calls.equals(List.of(5000, 5000, 1)) && first.inserted() == 10001 && first.updated() == 0, "Batch boundaries and inserts");
            check(first.failures().isEmpty() && first.uncertain() == 0, "Success counters");
            docs.set(0, new Document(keyField, "key-0").append("sampleSize", 21));
            var repeated = MongoBulkUpserts.save(collection, docs, keyField, Function.identity());
            check(repeated.updated() == 10001 && repeated.inserted() == 0 && database.size() == 10001, "Idempotent repeat");
            check(database.get("key-0").getInteger("sampleSize") == 21, "Existing document replaced");
            int before = calls.size();
            MongoBulkUpserts.save(collection, List.<Document>of(), keyField, Function.identity());
            check(calls.size() == before, "Empty input does not call driver");
        }

        List<Document> small = List.of(new Document("id", "a"), new Document("id", "b"), new Document("id", "c"));
        var partial = MongoBulkUpserts.save(fake(ops -> {
            throw new MongoBulkWriteException(result(1, List.of(new BulkWriteUpsert(2, new BsonString("c")))),
                    List.of(new BulkWriteError(11000, "not logged", new BsonDocument(), 0)), null, new ServerAddress(), Set.of());
        }), small, "id", Function.identity());
        check(partial.inserted() == 1 && partial.updated() == 1 && partial.failures().size() == 1
                && partial.failures().get(0).contains("a") && partial.uncertain() == 0, "Partial error retains successes and key");

        var uncertain = MongoBulkUpserts.save(fake(ops -> { throw new IllegalStateException("secret must not appear"); }),
                small, "id", Function.identity());
        check(uncertain.uncertain() == 3 && uncertain.inserted() == 0 && uncertain.updated() == 0
                && !uncertain.failures().toString().contains("secret"), "Transport uncertainty and sanitized logging");
        var concern = MongoBulkUpserts.save(fake(ops -> {
            throw new MongoBulkWriteException(result(3, List.of()), List.of(),
                    new WriteConcernError(64, "WriteConcernFailed", "not logged", new BsonDocument()), new ServerAddress(), Set.of());
        }), small, "id", Function.identity());
        check(concern.uncertain() == 3 && concern.updated() == 0, "Write concern uncertainty");
        List<Integer> valid = new ArrayList<>();
        var mapping = MongoBulkUpserts.save(fake(ops -> { valid.add(ops.size()); return result(ops.size(), List.of()); }),
                List.of(new Document(), new Document("id", "good")), "id", Function.identity());
        check(mapping.failures().size() == 1 && mapping.updated() == 1 && valid.equals(List.of(1)), "Bad input does not block valid input");
        System.out.println("MongoBulkUpsertsTest: passed (offline)");
    }

    private static BulkWriteResult result(int matched, List<BulkWriteUpsert> upserts) {
        return BulkWriteResult.acknowledged(0, matched, 0, 0, upserts, List.of());
    }

    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> fake(Function<List<ReplaceOneModel<Document>>, BulkWriteResult> write) {
        return (MongoCollection<Document>) Proxy.newProxyInstance(MongoCollection.class.getClassLoader(),
                new Class<?>[]{MongoCollection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("bulkWrite")) throw new AssertionError("Unexpected per-record call: " + method.getName());
                    check(!((BulkWriteOptions) args[1]).isOrdered(), "Unordered bulk");
                    return write.apply((List<ReplaceOneModel<Document>>) args[0]);
                });
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
