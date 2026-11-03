package avia.service;

import avia.client.AeroDataBoxClient.HttpStatusException;
import avia.model.FlightBatch;
import avia.storage.MongoAeroDataBoxRawStorage;
import org.bson.Document;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Resumable collection only; no normalization or analytics. */
public final class AeroDataBoxBulkCollector {
    public interface Store {
        boolean exists(String key);
        boolean insertIfAbsent(Document document);
    }
    @FunctionalInterface public interface Fetch { String get(FlightBatch batch) throws IOException, InterruptedException; }
    @FunctionalInterface public interface Pause { void sleep(long millis) throws InterruptedException; }
    @FunctionalInterface public interface Backup { void save(FlightBatch batch, String json) throws IOException; }
    public record Result(int planned, int existed, int requested, int attempts, int saved, int deferred,
                         List<String> failures, List<String> backupWarnings) { }

    public static String key(FlightBatch batch) {
        return String.format(Locale.ROOT, "%s|%s|%02d|%02d", batch.airport(), batch.date(),
                batch.from().getHour(), batch.to().equals(LocalTime.MIDNIGHT) ? 24 : batch.to().getHour());
    }
    public static Path relativePath(FlightBatch batch) {
        return Path.of(batch.date().toString(), batch.fileName().replace(".csv", ".json"));
    }

    public Result run(List<FlightBatch> batches, boolean dryRun, int maxBatches, Store store,
            Fetch fetch, Pause pause, Backup backup, Clock clock, Consumer<String> log) throws InterruptedException {
        int existed = 0, requested = 0, attempts = 0, saved = 0, deferred = 0;
        boolean authFailed = false;
        List<String> failures = new ArrayList<>(), warnings = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            FlightBatch batch = batches.get(i);
            String key = key(batch), prefix = "[" + (i + 1) + "/" + batches.size() + "] " + key + " ... ";
            // An unreadable store aborts: never spend quota when existence cannot be checked.
            if (store.exists(key)) { existed++; log.accept(prefix + "SKIPPED already exists"); continue; }
            if (authFailed || requested >= maxBatches) { deferred++; log.accept(prefix + "DEFERRED"); continue; }
            requested++;
            if (dryRun) { log.accept(prefix + "WOULD REQUEST"); continue; }
            log.accept(prefix + "REQUESTING");
            try {
                String json = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    attempts++;
                    try { json = fetch.get(batch); break; }
                    catch (IOException e) {
                        int status = e instanceof HttpStatusException h ? h.status() : 0;
                        if (status == 401 || status == 403) {
                            authFailed = true;
                            log.accept("API key / subscription access rejected (HTTP " + status + "); remaining new batches deferred");
                        }
                        boolean transientError = status == 429 || status >= 500 && status <= 599
                                || e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.ConnectException;
                        if (!transientError || attempt == 3) throw e;
                        long delay = status == 429 ? 30_000L * attempt : 2_000L * attempt;
                        log.accept(prefix + "RETRY " + (attempt + 1) + "/3 after " + delay / 1000 + "s");
                        pause.sleep(delay);
                    }
                }
                Instant collected = clock.instant();
                Document doc = MongoAeroDataBoxRawStorage.toDocument(relativePath(batch), json, clock.instant());
                Document payload = doc.get("payload", Document.class);
                if (!(payload.get("arrivals") instanceof List<?> || payload.get("departures") instanceof List<?>)) {
                    throw new IllegalArgumentException("Response is not FIDS");
                }
                doc.put("collected_at", Date.from(collected));
                // Preserve compatibility with the existing normalizer's source timestamp field.
                doc.put("collected_at_utc", Date.from(collected));
                try { backup.save(batch, json); }
                catch (IOException e) { warnings.add(key + " (" + e.getClass().getSimpleName() + ")"); }
                if (store.insertIfAbsent(doc)) { saved++; log.accept(prefix + "SAVED"); }
                else { existed++; log.accept(prefix + "SKIPPED inserted concurrently"); }
            } catch (IOException | RuntimeException e) {
                String error = key + " (" + e.getClass().getSimpleName() + ")";
                failures.add(error); log.accept(prefix + "FAILED " + e.getClass().getSimpleName());
            }
        }
        return new Result(batches.size(), existed, requested, attempts, saved, deferred,
                List.copyOf(failures), List.copyOf(warnings));
    }
}
