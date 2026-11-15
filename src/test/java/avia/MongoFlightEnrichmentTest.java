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
import avia.common.flight.model.FlightHistoricalStats;
import avia.task3.service.MongoOfferEnrichmentMapper;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.*;

/** Standalone offline tests: no Atlas or API access. */
public final class MongoFlightEnrichmentTest {
    public static void main(String[] args) {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");
        var stats = new FlightHistoricalStats("DP|6525|SVO|OVB", 7, 58.0, 121.857143,
                70.0, 130.428571, 5.0 / 7, 5.0 / 7, 3.0 / 7, 0, -3.0);
        check(stats.equals(MongoOfferEnrichmentMapper.readStats(MongoFlightStatisticsStorage.toDocument(stats, now))), "stats round trip");
        var mapper = new MongoOfferEnrichmentMapper();
        Document raw = new Document("_id", new ObjectId()).append("airline", " dp ")
                .append("flight_number", 6525).append("origin_airport", "svo")
                .append("destination_airport", "OVB").append("departure_at", "2026-11-05T22:45:00+03:00")
                .append("price", 4773).append("currency", "rub").append("transfers", 0)
                .append("duration", 245).append("_collected_at", Date.from(now));
        String original = raw.toJson();
        var history = Map.of(stats.flightServiceKey(), stats);
        Document matched = mapper.map(raw, history, now);
        check("MATCHED".equals(matched.getString("matchStatus")), "numeric flight number match");
        check(matched.get("offer", Document.class).getLong("price") == 4773, "price preserved");
        check(matched.get("offer", Document.class).getString("currency").equals("RUB"), "currency normalized");
        check(matched.get("historicalStats", Document.class).getInteger("sampleSize") == 7, "history present");
        check(!matched.get("historicalStats", Document.class).containsKey("computedAt"), "no invented computation time");
        check(original.equals(raw.toJson()), "raw unchanged");
        check(matched.get("_collected_at").equals(raw.get("_collected_at")), "collection timestamp preserved");
        check(matched.get("sourceOfferId").equals(mapper.map(raw, history, now.plusSeconds(60)).get("sourceOfferId")), "stable upsert identity");
        Document nextSnapshot = new Document(raw).append("_id", new ObjectId());
        check(!matched.get("sourceOfferId").equals(mapper.map(nextSnapshot, history, now).get("sourceOfferId")), "separate snapshots");
        Document unmatched = mapper.map(raw, Map.of(), now);
        check("NOT_MATCHED".equals(unmatched.getString("matchStatus")) && unmatched.get("historicalStats") == null, "left join");
        check(mapper.map(raw, history, now).get("sourceOfferId").equals(unmatched.get("sourceOfferId")), "unmatched can be updated to matched");
        Document other = new Document(raw).append("flight_number", "6526");
        check("NOT_MATCHED".equals(mapper.map(other, history, now).get("matchStatus")), "different number");
        Document future = new Document(raw).append("departure_at", "2027-01-01T01:00:00+03:00");
        check("MATCHED".equals(mapper.map(future, history, now).get("matchStatus")), "date not join key");
        Document legacy = new Document(raw); legacy.remove("currency");
        Document invalid = mapper.map(legacy, history, now);
        check("INVALID_OFFER".equals(invalid.get("matchStatus")) && invalid.get("rawOffer") != null, "missing currency preserved and reported");
        legacy.append("_response_metadata", new Document("currency", "rub"));
        check("MATCHED".equals(mapper.map(legacy, history, now).get("matchStatus")), "envelope currency fallback");
        check("INVALID_OFFER".equals(mapper.map(new Document(raw).append("price", null), history, now).get("matchStatus")), "no fake zero price");
        System.out.println("MongoFlightEnrichmentTest: OK");
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
