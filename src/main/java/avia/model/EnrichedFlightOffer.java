package avia.model;

import java.util.Objects;

/** Historical statistics are null when no exact service-key match exists. */
public record EnrichedFlightOffer(AviasalesFlightOffer offer, FlightHistoricalStats historicalStats) {
    public enum MatchStatus { MATCHED, NOT_MATCHED }

    public EnrichedFlightOffer {
        Objects.requireNonNull(offer, "offer");
        if (historicalStats != null && !offer.flightServiceKey().equals(historicalStats.flightServiceKey())) {
            throw new IllegalArgumentException("Historical service key differs from offer");
        }
    }

    public String flightServiceKey() { return offer.flightServiceKey(); }
    public MatchStatus matchStatus() { return historicalStats == null ? MatchStatus.NOT_MATCHED : MatchStatus.MATCHED; }
}
