package avia.renderer;

import avia.model.OfferDashboardData;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class OfferDashboardHtmlRenderer {
    public void render(Path output, OfferDashboardData data) throws IOException {
        String template;
        try (var stream = getClass().getResourceAsStream("/avia/offer-dashboard.html")) {
            if (stream == null) throw new IOException("Offer dashboard resource missing");
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String json = json(data).toString().replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, template.replace("__OFFER_DATA__", json), StandardCharsets.UTF_8);
    }
    public static JSONObject json(OfferDashboardData data) {
        JSONArray rows = new JSONArray();
        for (var row : data.rows()) {
            JSONObject item = new JSONObject().put("id", nullable(row.sourceId())).put("status", row.status())
                    .put("collectedAt", nullable(row.collectedAt())).put("enrichedAt", nullable(row.enrichedAt()))
                    .put("error", nullable(row.validationError()));
            if (row.enriched() != null) {
                var value = row.enriched(); var offer = value.offer(); var stats = value.historicalStats();
                item.put("key", value.flightServiceKey()).put("airline", offer.airlineIata())
                        .put("number", offer.flightNumber()).put("origin", offer.originAirport())
                        .put("destination", offer.destinationAirport()).put("departureAt", offer.departureAt().toString())
                        .put("price", offer.price()).put("currency", offer.currency()).put("transfers", offer.transfers())
                        .put("duration", offer.durationMin());
                JSONObject history = null;
                if (stats != null) history = new JSONObject().put("n", stats.sampleSize())
                        .put("median", nullable(stats.medianDepartureDelayMin())).put("mean", nullable(stats.meanDepartureDelayMin()))
                        .put("arrivalMedian", nullable(stats.medianArrivalDelayMin())).put("arrivalMean", nullable(stats.meanArrivalDelayMin()))
                        .put("p15", nullable(stats.departureDelay15Rate())).put("p30", nullable(stats.departureDelay30Rate()))
                        .put("p60", nullable(stats.departureDelay60Rate())).put("cancellation", stats.cancellationRate())
                        .put("recovery", nullable(stats.medianDelayRecoveryMin()));
                item.put("history", history == null ? JSONObject.NULL : history);
            }
            rows.put(item);
        }
        return new JSONObject().put("generatedAt", data.generatedAt().toString()).put("rows", rows);
    }
    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value instanceof Number ? value : value.toString();
    }
}
