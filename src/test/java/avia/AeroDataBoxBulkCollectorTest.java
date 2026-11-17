package avia;
import avia.common.aerodatabox.*;
import avia.common.aerodatabox.cli.*;
import avia.common.aerodatabox.mongo.*;
import avia.common.aviasales.*;
import avia.common.aviasales.cli.*;
import avia.common.flight.*;
import avia.common.flight.model.*;
import avia.common.flight.mongo.*;
import avia.common.flight.storage.*;
import avia.common.mongo.*;
import avia.task1.analysis.*;
import avia.task1.model.*;
import avia.task1.report.*;
import avia.task2.analysis.*;
import avia.task2.model.*;
import avia.task2.report.*;
import avia.task3.analysis.*;
import avia.task3.cli.*;
import avia.task3.model.*;
import avia.task3.mongo.*;
import avia.task3.report.*;
import avia.task3.service.*;
import avia.task4.analysis.*;
import avia.task4.report.*;
import avia.task5.delay.model.*;
import avia.task5.delay.mongo.*;
import avia.task5.delay.service.*;
import avia.task5.model.*;
import avia.task5.price.service.*;
import avia.common.aerodatabox.AeroDataBoxClient.HttpStatusException;
import org.bson.Document;
import java.time.*;
import java.util.*;

/** Offline orchestration tests: API and Mongo are replaced by in-memory test doubles. */
public final class AeroDataBoxBulkCollectorTest {
    static final class Store implements AeroDataBoxBulkCollector.Store {
        final Map<String, Document> docs = new HashMap<>();
        int writes;
        public boolean exists(String key) { return docs.containsKey(key); }
        public boolean insertIfAbsent(Document doc) {
            writes++;
            return docs.putIfAbsent(doc.getString("batch_key"), doc) == null;
        }
    }
    public static void main(String[] args) throws Exception {
        var options = AeroDataBoxBulkCollectorCli.parse(new String[]{"--from", "2026-10-04", "--to", "2026-10-24", "--dry-run"});
        var plan = FlightDatasetPlan.batches(options.from(), options.to(), options.airports());
        check(plan.size() == 210, "21 days, five airports, two windows");
        check(AeroDataBoxBulkCollector.key(plan.get(0)).equals("SVO|2026-10-04|00|12"), "First key");
        check(AeroDataBoxBulkCollector.key(plan.get(1)).equals("SVO|2026-10-04|12|24"), "Midnight key");
        var collector = new AeroDataBoxBulkCollector();
        Clock clock = Clock.fixed(Instant.parse("2026-10-25T15:00:00Z"), ZoneOffset.UTC);
        Store store = new Store();
        List<String> output = new ArrayList<>();
        var dry = collector.run(plan, true, Integer.MAX_VALUE, store, b -> { throw new AssertionError("API in dry-run"); },
                ms -> { throw new AssertionError("Pause in dry-run"); }, (b, json) -> { throw new AssertionError("Backup in dry-run"); }, clock, output::add);
        check(dry.requested() == 210 && dry.saved() == 0 && store.writes == 0 && output.size() == 210, "Dry-run read-only plan");
        String payload = "{\"arrivals\":[],\"departures\":[]}";
        int[] calls = {0}, backups = {0};
        var initial = collector.run(plan, false, 2, store, b -> { calls[0]++; return payload; }, ms -> {},
                (b, json) -> { backups[0]++; check(json.equals(payload), "Exact backup content"); }, clock, s -> {});
        check(initial.requested() == 2 && initial.saved() == 2 && calls[0] == 2 && initial.deferred() == 208, "Max new batches");
        var doc = store.docs.get(AeroDataBoxBulkCollector.key(plan.get(0)));
        check(doc.get("payload") instanceof Document && doc.getDate("collected_at").toInstant().equals(clock.instant())
                && doc.get("collected_at").equals(doc.get("collected_at_utc")), "Payload and actual collection timestamp");
        var again = collector.run(plan.subList(0, 2), false, 2, store, b -> { throw new AssertionError("Existing batch fetched"); },
                ms -> {}, (b, json) -> { throw new AssertionError("Existing backup touched"); }, clock, s -> {});
        check(again.existed() == 2 && again.requested() == 0 && store.writes == 2, "Resume skips existing API and writes");
        var limitedDry = collector.run(plan, true, 2, store, b -> { throw new AssertionError(); }, ms -> {},
                (b, j) -> {}, clock, s -> {});
        check(limitedDry.existed() == 2 && limitedDry.requested() == 2 && limitedDry.deferred() == 206, "Existing do not consume limit");
        Store retryStore = new Store(); int[] attempt = {0}; List<Long> sleeps = new ArrayList<>();
        var retried = collector.run(plan.subList(0, 1), false, 1, retryStore, b -> {
            if (++attempt[0] < 3) throw new HttpStatusException(attempt[0] == 1 ? 429 : 503);
            return payload;
        }, sleeps::add, (b, j) -> {}, clock, s -> {});
        check(retried.attempts() == 3 && retried.saved() == 1 && sleeps.equals(List.of(30000L, 4000L)), "Bounded retry and backoff");
        var failed = collector.run(plan.subList(0, 2), false, 2, new Store(), b -> { throw new HttpStatusException(503); },
                ms -> {}, (b, j) -> {}, clock, s -> {});
        check(failed.attempts() == 6 && failed.failures().size() == 2, "Continue after exhausted retry");
        var auth = collector.run(plan, false, 2, new Store(), b -> { throw new HttpStatusException(403); },
                ms -> { throw new AssertionError("Auth retry"); }, (b, j) -> {}, clock, s -> {});
        check(auth.attempts() == 1 && auth.failures().size() == 1 && auth.deferred() == 209, "Stop new requests on auth failure");
        var invalid = collector.run(plan.subList(0, 1), false, 1, new Store(), b -> "{\"message\":\"error\"}",
                ms -> {}, (b, j) -> {}, clock, s -> {});
        check(invalid.saved() == 0 && invalid.failures().size() == 1, "Error payload not saved");
        for (String[] bad : List.of(new String[]{"--from", "2026-10-24", "--to", "2026-10-04"},
                new String[]{"--from", "2026-10-04", "--to", "2026-10-24", "--max-batches", "0"},
                new String[]{"--from", "2026-10-04", "--to", "2026-10-24", "--airports", "SVO,SVO"})) {
            try { AeroDataBoxBulkCollectorCli.parse(bad); throw new AssertionError("Invalid options accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("AeroDataBoxBulkCollectorTest: passed (offline)");
        System.out.println("Simulated dry-run 2026-10-04..2026-10-24, empty store: planned=210, existed=0, would request=210");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
