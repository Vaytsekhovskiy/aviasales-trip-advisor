package avia;

import avia.model.*;
import avia.renderer.OfferDashboardHtmlRenderer;
import avia.service.MongoOfferEnrichmentMapper;
import avia.storage.MongoEnrichedOfferReader;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class OfferDashboardTest {
    public static void main(String[] args) throws Exception {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        var stats = new FlightHistoricalStats("DP|6525|SVO|OVB", 7, 58.0, 121.857143, 70.0,
                130.428571, 5.0/7, 5.0/7, 3.0/7, 0, -3.0);
        Document raw = new Document("_id", new ObjectId()).append("airline", "DP").append("flight_number", "6525")
                .append("origin_airport", "SVO").append("destination_airport", "OVB")
                .append("departure_at", "2026-11-05T22:45:00+03:00").append("price", 4773)
                .append("currency", "rub").append("transfers", 0).append("duration", 245).append("_collected_at", Date.from(now));
        var mapper = new MongoOfferEnrichmentMapper();
        var matched = MongoEnrichedOfferReader.toRow(mapper.map(raw, Map.of(stats.flightServiceKey(), stats), now));
        var absent = MongoEnrichedOfferReader.toRow(mapper.map(new Document(raw).append("_id", new ObjectId())
                .append("flight_number", "6945").append("destination_airport", "AER"), Map.of(), now));
        Document invalid = mapper.map(new Document(raw).append("_id", new ObjectId()).append("currency", null), Map.of(), now);
        invalid.put("validationError", "Missing currency </script><script>alert(1)</script>");
        var broken = MongoEnrichedOfferReader.toRow(invalid);
        var data = new OfferDashboardData(now, List.of(matched, absent, broken));
        var json = OfferDashboardHtmlRenderer.json(data);
        var row = json.getJSONArray("rows").getJSONObject(0);
        check(row.getJSONObject("history").get("median") instanceof Number, "numeric metrics");
        check(row.getJSONObject("history").getDouble("p30") == stats.departureDelay30Rate(), "no recomputation");
        check(row.getLong("price") == 4773 && row.getString("departureAt").endsWith("+03:00"), "price and offset preserved");
        check(json.getJSONArray("rows").getJSONObject(1).isNull("history"), "missing history is null");
        check(broken.enriched() == null && broken.status().equals("INVALID_OFFER"), "invalid retained");
        check(matched.collectedAt().equals(now), "collection time");
        Document inconsistent = mapper.map(raw, Map.of(), now); inconsistent.put("matchStatus", "MATCHED");
        try { MongoEnrichedOfferReader.toRow(inconsistent); throw new AssertionError("inconsistent data accepted"); }
        catch (IllegalArgumentException expected) { }
        var renderer = new OfferDashboardHtmlRenderer();
        Path path = Path.of("target/offer-dashboard-test.html");
        renderer.render(path, data);
        String html = Files.readString(path);
        check(!html.contains("Missing currency </script>"), "script injection escaped");
        check(!html.contains("__OFFER_DATA__"), "placeholder replaced");
        check(!html.contains("<script src="), "offline scripts");
        renderer.render(Path.of("target/offer-dashboard-empty.html"), new OfferDashboardData(now, List.of()));
        System.out.println("OfferDashboardTest: OK (synthetic fixtures, not Mongo data)");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
