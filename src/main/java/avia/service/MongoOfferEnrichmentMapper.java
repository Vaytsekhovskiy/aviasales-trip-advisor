package avia.service;

import avia.model.*;
import avia.storage.MongoFlightStatisticsStorage;
import org.bson.Document;
import org.json.*;
import java.time.Instant;
import java.util.*;

/** Mongo adapter only: parsing and matching remain owned by existing services. */
public final class MongoOfferEnrichmentMapper {
    private final AviasalesFlightPreprocessor parser = new AviasalesFlightPreprocessor();
    private final FlightEnrichmentService enrichment = new FlightEnrichmentService();

    public Document map(Document raw, Map<String, FlightHistoricalStats> history, Instant now) {
        Object id = raw.get("_id");
        if (id == null) throw new IllegalArgumentException("Missing source _id");
        // Typed BSON representation avoids collisions between different BSON _id types.
        String sourceId = new Document("id", id).toJson();
        Document result = new Document("sourceOfferId", sourceId)
                .append("sourceRawId", id).append("sourceCollection", "raw_prices_for_dates")
                .append("_collected_at", raw.get("_collected_at"))
                .append("snapshot_id", raw.get("snapshot_id"))
                .append("_route", raw.get("_route"))
                .append("enrichedAt", Date.from(now))
                .append("rawOffer", new Document(raw));
        Object currency = raw.get("currency");
        if (currency == null && raw.get("_response_metadata") instanceof Document metadata)
            currency = metadata.get("currency");
        if (!(currency instanceof String text) || text.isBlank())
            return invalid(result, "Missing currency in offer and response metadata");
        JSONObject row = new JSONObject(raw.toJson());
        Object number = raw.get("flight_number");
        if (number instanceof Number) {
            try { row.put("flight_number", new java.math.BigDecimal(number.toString()).toBigIntegerExact().toString()); }
            catch (ArithmeticException | NumberFormatException e) { return invalid(result, "Non-integral flight_number"); }
        }
        var parsed = parser.process(new JSONObject().put("success", true).put("currency", currency)
                .put("data", new JSONArray().put(row)).toString());
        if (parsed.isEmpty()) return invalid(result, "Invalid or missing offer fields; see rawOffer");
        EnrichedFlightOffer value = enrichment.enrich(parsed, history).get(0);
        var offer = value.offer();
        Document normalized = new Document("airlineIata", offer.airlineIata())
                .append("flightNumber", offer.flightNumber()).append("originAirport", offer.originAirport())
                .append("destinationAirport", offer.destinationAirport())
                .append("departureAt", offer.departureAt().toString()).append("price", offer.price())
                .append("currency", offer.currency()).append("transfers", offer.transfers())
                .append("durationMin", offer.durationMin());
        Document stats = null;
        if (value.historicalStats() != null) {
            stats = MongoFlightStatisticsStorage.toDocument(value.historicalStats(), now);
            // The enrichment timestamp must not pretend to be the statistics computation time.
            stats.remove("computedAt");
        }
        return result.append("flightServiceKey", value.flightServiceKey()).append("offer", normalized)
                .append("matchStatus", value.matchStatus().name()).append("historicalStats", stats);
    }

    private static Document invalid(Document result, String reason) {
        return result.append("matchStatus", "INVALID_OFFER").append("validationError", reason)
                .append("flightServiceKey", null).append("offer", null).append("historicalStats", null);
    }

    public static FlightHistoricalStats readStats(Document d) {
        String key = d.getString("flightServiceKey");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Missing statistics key");
        int size = new java.math.BigDecimal(d.get("sampleSize").toString()).intValueExact();
        Double cancellation = metric(d, "cancellationRate");
        if (size < 0 || cancellation == null) throw new IllegalArgumentException("Invalid statistics");
        return new FlightHistoricalStats(key, size, metric(d, "medianDepartureDelayMin"),
                metric(d, "meanDepartureDelayMin"), metric(d, "medianArrivalDelayMin"),
                metric(d, "meanArrivalDelayMin"), metric(d, "departureDelay15Rate"),
                metric(d, "departureDelay30Rate"), metric(d, "departureDelay60Rate"),
                cancellation, metric(d, "medianDelayRecoveryMin"));
    }
    private static Double metric(Document d, String name) {
        Object value = d.get(name);
        if (value == null) return null;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException("Invalid metric: " + name);
        return n.doubleValue();
    }
}
