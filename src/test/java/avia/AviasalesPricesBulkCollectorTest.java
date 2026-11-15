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
import avia.task5.price.model.*;
import avia.task5.price.service.*;
import org.bson.Document;
import java.time.*;
import java.util.*;

/** Standalone offline checks; no real token, API or MongoDB. */
public final class AviasalesPricesBulkCollectorTest {
    public static void main(String[] args) throws Exception {
        var options = AviasalesPricesBulkCollectorCli.parse(new String[]{"--month", "2026-11", "--dry-run"});
        var routes = AviasalesPricesBulkCollector.routes(options.airports());
        check(routes.size() == 20 && new HashSet<>(routes).size() == 20, "Twenty distinct directed routes");
        check(routes.get(0).key().equals("SVO-LED") && routes.get(4).key().equals("LED-SVO"), "Stable route order");
        String json = """
                {"success":true,"currency":"rub","extra":{"unknown":true},"data":[
                  {"airline":" dp ","flight_number":"DP 6525","origin_airport":"svo","destination_airport":"OVB",
                   "departure_at":"2026-11-05T22:45:00+03:00","price":4849,"gate":"example","nested":{"a":1},"nullable":null}
                ]}
                """;
        Instant time = Instant.parse("2026-10-31T12:00:00Z");
        var docs = AviasalesPriceSnapshots.documents(json, "SVO-OVB", options.month(), time);
        var doc = docs.get(0);
        check(doc.getString("currency").equals("rub") && doc.get("nested") instanceof Document
                && doc.containsKey("nullable") && doc.get("nullable") == null, "Raw fields and nested BSON retained");
        check(doc.getDate("_collected_at").toInstant().equals(time) && doc.getString("_endpoint").equals("prices_for_dates"), "Metadata");
        check(doc.get("_response_metadata", Document.class).containsKey("extra"), "Envelope metadata retained");
        check(doc.getString("snapshot_id").equals(AviasalesPriceSnapshots.documents(json, "SVO-OVB", options.month(), time).get(0).getString("snapshot_id")), "Stable snapshot identity");
        check(!doc.getString("snapshot_id").equals(AviasalesPriceSnapshots.documents(json, "SVO-OVB", options.month(), time.plusSeconds(1)).get(0).getString("snapshot_id")), "New collection time creates new snapshot");
        check(!doc.getString("snapshot_id").equals(AviasalesPriceSnapshots.documents(json.replace("4849", "5220"), "SVO-OVB", options.month(), time).get(0).getString("snapshot_id")), "Price preserved in identity");
        var original = new org.json.JSONObject(json);
        var reordered = new org.json.JSONObject().put("data", original.getJSONArray("data")).put("extra", original.get("extra"))
                .put("currency", "rub").put("success", true);
        check(doc.get("snapshot_id").equals(AviasalesPriceSnapshots.documents(reordered.toString(), "SVO-OVB", options.month(), time).get(0).get("snapshot_id")), "JSON key order independent");
        var audit = new AviasalesOfferAudit().audit(List.of(doc, new Document(doc).append("flight_number", 6525),
                new Document(doc).append("flight_number", "6526"), new Document("airline", "DP")), Set.of("DP|6525|SVO|OVB"));
        check(audit.total() == 4 && audit.validKeys() == 3 && audit.matched() == 2 && audit.notMatched() == 2
                && audit.matchRate() == 50.0, "Exact key match; numeric flight number; invalid identity included in denominator");
        check(audit.examples().equals(List.of("DP|6525|SVO|OVB")), "Distinct examples");
        check(new AviasalesOfferAudit().audit(List.of(), Set.of()).matchRate() == null, "Empty audit is not zero percent");
        check(AviasalesOfferAudit.serviceKey("DP", "6525", null, "OVB").isEmpty(), "Never substitute route/city for missing airport");

        var runner = new AviasalesPricesBulkCollector(); int[] calls = {0};
        Map<String, Document> saved = new HashMap<>();
        var run = runner.run(routes, options.month(), 2, route -> { calls[0]++; return json; }, documents -> {
            int inserted = 0; for (Document d : documents) if (saved.putIfAbsent(d.getString("snapshot_id"), d) == null) inserted++; return inserted;
        }, ms -> {}, Clock.fixed(time, ZoneOffset.UTC), s -> {});
        check(run.attempts() == 2 && run.completed() == 2 && run.saved() == 2 && calls[0] == 2, "Max requests");
        var repeat = runner.run(routes, options.month(), 2, route -> json, documents -> {
            int inserted = 0; for (Document d : documents) if (saved.putIfAbsent(d.getString("snapshot_id"), d) == null) inserted++; return inserted;
        }, ms -> {}, Clock.fixed(time, ZoneOffset.UTC), s -> {});
        check(repeat.saved() == 0 && saved.size() == 2, "Same snapshot replay");
        int[] retries = {0}; List<Long> delays = new ArrayList<>();
        var retried = runner.run(routes, options.month(), 2, route -> {
            if (++retries[0] == 1) throw new AviasalesClient.HttpStatusException(429); return json;
        }, List::size, delays::add, Clock.fixed(time, ZoneOffset.UTC), s -> {});
        check(retried.attempts() == 2 && retried.completed() == 1 && delays.equals(List.of(30000L)), "Retries consume request budget");
        var auth = runner.run(routes, options.month(), 20, route -> { throw new AviasalesClient.HttpStatusException(401); },
                List::size, ms -> { throw new AssertionError("Auth retry"); }, Clock.systemUTC(), s -> {});
        check(auth.attempts() == 1 && auth.failures().size() == 1, "Stop on authorization failure");
        var exhausted = runner.run(routes.subList(0, 2), options.month(), 20, route -> { throw new AviasalesClient.HttpStatusException(503); },
                List::size, ms -> {}, Clock.systemUTC(), s -> {});
        check(exhausted.attempts() == 6 && exhausted.failures().size() == 2, "Bounded retries continue next route");
        var empty = runner.run(routes, options.month(), 2, route -> "{\"success\":true,\"data\":[],\"currency\":\"rub\"}",
                documents -> { throw new AssertionError("Empty write"); }, ms -> {}, Clock.systemUTC(), s -> {});
        check(empty.empty() == 2 && empty.failures().isEmpty(), "Empty response is successful");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        List<String> queries = new ArrayList<>();
        server.createContext("/aviasales/v3/prices_for_dates", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            var bytes = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, bytes.size());
            exchange.getResponseBody().write(bytes.toByteArray()); exchange.close();
        });
        server.start();
        try {
            var client = new AviasalesClient("test-only", java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    java.net.http.HttpClient.newHttpClient());
            check(client.fetchPricesForDates("SVO", "OVB", options.month()).equals(json), "Existing client decodes gzip raw response");
            for (String parameter : List.of("origin=SVO", "destination=OVB", "departure_at=2026-11", "one_way=true", "direct=true",
                    "unique=false", "sorting=price", "currency=rub", "market=ru", "limit=1000", "page=1", "token=test-only"))
                check(Arrays.asList(queries.get(0).split("&")).contains(parameter), "Request parameter " + parameter);
        } finally { server.stop(0); }
        System.out.println("AviasalesPricesBulkCollectorTest: passed (offline)");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
