package avia.service;

import avia.AviasalesClient.HttpStatusException;
import com.mongodb.MongoBulkWriteException;
import org.bson.Document;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded collection orchestration; maxRequests caps actual HTTP attempts, including retries. */
public final class AviasalesPricesBulkCollector {
    public record Route(String origin, String destination) {
        public String key() { return origin + "-" + destination; }
    }
    public record Result(int completed, int attempts, int empty, int received, int saved,
                         int uncertainOffers, List<String> failures) { }
    @FunctionalInterface public interface Fetch { String get(Route route) throws IOException, InterruptedException; }
    @FunctionalInterface public interface Save { int save(List<Document> documents); }
    @FunctionalInterface public interface Pause { void sleep(long milliseconds) throws InterruptedException; }

    public static List<Route> routes(List<String> airports) {
        if (airports.size() < 2 || new HashSet<>(airports).size() != airports.size()
                || airports.stream().anyMatch(a -> !a.matches("[A-Z]{3}"))) throw new IllegalArgumentException("Distinct IATA airports required");
        List<Route> result = new ArrayList<>();
        for (String origin : airports) for (String destination : airports)
            if (!origin.equals(destination)) result.add(new Route(origin, destination));
        return List.copyOf(result);
    }

    public Result run(List<Route> routes, YearMonth month, int maxRequests, Fetch fetch, Save save,
            Pause pause, Clock clock, Consumer<String> log) throws InterruptedException {
        int completed = 0, attempts = 0, empty = 0, received = 0, saved = 0, uncertain = 0;
        List<String> failures = new ArrayList<>();
        boolean authFailed = false;
        for (int i = 0; i < routes.size() && attempts < maxRequests && !authFailed; i++) {
            Route route = routes.get(i);
            String prefix = "[" + (i + 1) + "/" + routes.size() + "] " + route.origin() + " → " + route.destination();
            List<Document> documents;
            try {
                String json = null;
                for (int retry = 0; retry < 3; retry++) {
                    attempts++;
                    log.accept(prefix + " ... requesting Aviasales (attempt " + (retry + 1) + "/3)...");
                    long requestStarted = System.nanoTime();
                    try {
                        json = fetch.get(route);
                        log.accept(prefix + " ... response received (" + elapsed(requestStarted) + "); parsing...");
                        break;
                    }
                    catch (HttpStatusException e) {
                        int code = e.status();
                        log.accept(prefix + " ... HTTP " + code + " (" + elapsed(requestStarted) + ")");
                        if (code == 401 || code == 403) {
                            authFailed = true;
                            log.accept("Aviasales token/access denied: HTTP " + code + "; check AVIASALES_TOKEN and access");
                        }
                        if ((code != 429 && (code < 500 || code > 599)) || retry == 2 || attempts >= maxRequests) throw e;
                        long delay = code == 429 ? 30_000L * (retry + 1) : 2_000L * (retry + 1);
                        log.accept(prefix + " ... HTTP " + code + ", retry " + (retry + 2) + "/3 after " + delay / 1000 + "s");
                        pause.sleep(delay);
                    }
                }
                Instant collectedAt = clock.instant();
                documents = AviasalesPriceSnapshots.documents(json, route.key(), month, collectedAt);
                log.accept(prefix + " ... parsed " + documents.size() + " offers");
                completed++; received += documents.size();
                if (documents.isEmpty()) { empty++; log.accept(prefix + " ... EMPTY"); continue; }
                if (documents.size() >= 1000) log.accept(prefix + " ... page limit reached; dataset may be truncated (page=1)");
            } catch (IOException | RuntimeException e) {
                failures.add(route.key() + " (" + e.getClass().getSimpleName() + ")");
                log.accept(prefix + " ... FAILED " + e.getClass().getSimpleName());
                continue;
            }
            log.accept(prefix + " ... saving " + documents.size() + " offers to MongoDB...");
            long saveStarted = System.nanoTime();
            try {
                int inserted = save.save(documents);
                saved += inserted;
                log.accept(prefix + " ... " + inserted + " offers SAVED (received " + documents.size() + ", " + elapsed(saveStarted) + ")");
            } catch (MongoBulkWriteException e) {
                if (e.getWriteConcernError() == null) saved += e.getWriteResult().getUpserts().size();
                else uncertain += documents.size();
                failures.add(route.key() + " (partial Mongo write; inspect audit)");
                log.accept(prefix + " ... FAILED Mongo write (some snapshots may have been saved)");
            } catch (RuntimeException e) {
                uncertain += documents.size();
                failures.add(route.key() + " (" + e.getClass().getSimpleName() + "; write outcome unknown)");
                log.accept(prefix + " ... FAILED persistence; outcome unknown");
            }
        }
        return new Result(completed, attempts, empty, received, saved, uncertain, List.copyOf(failures));
    }
    private static String elapsed(long started) {
        return String.format(Locale.ROOT, "%.2fs", (System.nanoTime() - started) / 1e9);
    }
}
