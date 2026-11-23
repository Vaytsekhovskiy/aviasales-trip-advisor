package avia.task5.delay.service;
import avia.task5.delay.model.FlightProfileKey;
import avia.task5.delay.model.PredictableFlightProfile;
import java.util.*;
public final class PredictableFlightProfileService {
    public List<PredictableFlightProfile> intersect(Map<FlightProfileKey, Long> delay, Map<FlightProfileKey, Long> price) {
        return delay.entrySet().stream().filter(e -> price.containsKey(e.getKey()))
                .map(e -> new PredictableFlightProfile(e.getKey(), e.getValue(), price.get(e.getKey())))
                .sorted(Comparator.comparing(PredictableFlightProfile::profileKey)).toList();
    }
}
