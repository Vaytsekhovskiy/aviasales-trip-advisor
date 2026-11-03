package avia.model;

import java.time.Instant;
import java.util.List;

/** Read-only view of enriched snapshots; no recomputation of historical metrics. */
public record OfferDashboardData(Instant generatedAt, List<Row> rows) {
    public OfferDashboardData { rows = List.copyOf(rows); }
    public record Row(String sourceId, String status, EnrichedFlightOffer enriched,
                      Instant collectedAt, Instant enrichedAt, String validationError) { }
}
