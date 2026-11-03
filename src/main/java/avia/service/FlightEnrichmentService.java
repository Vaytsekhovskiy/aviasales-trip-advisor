package avia.service;

import avia.model.*;
import java.util.List;
import java.util.Map;

/** Left join preserving offer order and offers without historical statistics. */
public final class FlightEnrichmentService {
    public List<EnrichedFlightOffer> enrich(List<AviasalesFlightOffer> offers,
            Map<String, FlightHistoricalStats> history) {
        return offers.stream()
                .map(offer -> new EnrichedFlightOffer(offer, history.get(offer.flightServiceKey())))
                .toList();
    }
}
