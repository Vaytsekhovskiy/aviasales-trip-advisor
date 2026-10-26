package avia.storage;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.*;
import java.util.function.Function;
import static com.mongodb.client.model.Filters.eq;

/** Bounded unordered batches; transport/write-concern failures are explicitly uncertain. */
public final class MongoBulkUpserts {
    public static final int BATCH_SIZE = 5000;
    private MongoBulkUpserts() { }
    public record Result(int inserted, int updated, List<String> failures, int uncertain) { }

    public static <T> Result save(MongoCollection<Document> collection, Iterable<T> values,
            String keyField, Function<T, Document> mapper) {
        List<WriteModel<Document>> operations = new ArrayList<>();
        List<String> keys = new ArrayList<>(), failures = new ArrayList<>();
        int[] counts = new int[3]; // inserted, matched (including unchanged), uncertain
        int position = 0;
        int batchNumber = 0, processed = 0;
        for (T value : values) {
            position++;
            try {
                Document doc = mapper.apply(value);
                String key = doc.getString(keyField);
                if (key == null || key.isBlank()) throw new IllegalArgumentException("Missing key");
                operations.add(new ReplaceOneModel<>(eq(keyField, key), doc, new ReplaceOptions().upsert(true)));
                keys.add(key);
            } catch (RuntimeException e) {
                failures.add("Input #" + position + " (" + e.getClass().getSimpleName() + ")");
            }
            if (operations.size() == BATCH_SIZE) {
                processed += operations.size();
                flush(collection, operations, keys, failures, counts, ++batchNumber, processed);
            }
        }
        if (!operations.isEmpty()) {
            processed += operations.size();
            flush(collection, operations, keys, failures, counts, ++batchNumber, processed);
        }
        return new Result(counts[0], counts[1], List.copyOf(failures), counts[2]);
    }

    private static void flush(MongoCollection<Document> collection, List<WriteModel<Document>> operations,
            List<String> keys, List<String> failures, int[] counts, int batchNumber, int processed) {
        long started = System.nanoTime();
        int size = operations.size();
        int insertedBefore = counts[0], updatedBefore = counts[1], uncertainBefore = counts[2];
        int errorsBefore = failures.size();
        try {
            BulkWriteResult result = collection.bulkWrite(operations, new BulkWriteOptions().ordered(false));
            if (!result.wasAcknowledged()) throw new IllegalStateException("Unacknowledged write");
            counts[0] += result.getUpserts().size();
            counts[1] += result.getMatchedCount();
        } catch (MongoBulkWriteException e) {
            Set<Integer> failed = new HashSet<>();
            for (var error : e.getWriteErrors()) {
                failed.add(error.getIndex());
                failures.add(keys.get(error.getIndex()) + " (Mongo write code " + error.getCode() + ")");
            }
            if (e.getWriteConcernError() == null) {
                counts[0] += e.getWriteResult().getUpserts().size();
                counts[1] += e.getWriteResult().getMatchedCount();
            } else {
                counts[2] += operations.size() - failed.size();
                failures.add("Batch starting at " + keys.get(0) + ": write concern not confirmed; rerun safely");
            }
        } catch (RuntimeException e) {
            // Some writes may already have reached MongoDB. Do not call them inserted or failed.
            counts[2] += operations.size();
            failures.add("Batch starting at " + keys.get(0) + ": outcome unknown ("
                    + e.getClass().getSimpleName() + "); rerun safely");
        }
        System.out.printf(Locale.ROOT,
                "Batch #%d completed: documents=%d, inserted=%d, updated=%d, errors=%d, unconfirmed=%d, time=%.2fs; processed=%d%n",
                batchNumber, size, counts[0] - insertedBefore, counts[1] - updatedBefore,
                failures.size() - errorsBefore, counts[2] - uncertainBefore,
                (System.nanoTime() - started) / 1_000_000_000.0, processed);
        operations.clear();
        keys.clear();
    }
}
