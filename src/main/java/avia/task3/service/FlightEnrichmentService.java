package avia.task3.service;

import avia.common.flight.model.FlightHistoricalStats;
import avia.task3.model.AviasalesFlightOffer;
import avia.task3.model.EnrichedFlightOffer;
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
