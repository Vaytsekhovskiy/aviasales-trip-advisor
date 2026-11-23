package avia.task5.delay.service;

import avia.common.flight.model.FlightRecord;
import avia.task5.delay.model.FlightProfileKey;
import avia.task5.delay.model.PredictableFlightProfile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Java-side preparation boundary for Task 5 delay ML features; CatBoost remains in ml/. */
@Service
public class Task5DelayProfileService {
    private final FlightProfileAggregator profileAggregator = new FlightProfileAggregator();
    private final PredictableFlightProfileService predictableService = new PredictableFlightProfileService();

    public Map<FlightProfileKey, Long> aggregateDelayHistory(
            List<FlightRecord> flights) {
        return profileAggregator.delayCounts(flights);
    }

    public List<PredictableFlightProfile> selectPredictable(
            Map<FlightProfileKey, Long> delays,
            Map<FlightProfileKey, Long> prices,
            long minDelayFlights, long minPriceFlights) {
        Map<FlightProfileKey, Long> eligibleDelays = delays.entrySet().stream()
                .filter(entry -> entry.getValue() >= minDelayFlights)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<FlightProfileKey, Long> eligiblePrices = prices.entrySet().stream()
                .filter(entry -> entry.getValue() >= minPriceFlights)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return predictableService.intersect(eligibleDelays, eligiblePrices);
    }
}
