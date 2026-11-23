package avia.task4.service;

import avia.task4.analysis.AirlineStats;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application boundary for route-by-airline price comparison. */
@Service
public class Task4Service {
    public AirlineStats compare(List<AirlineStats.Group> groups, int minPairs, int minDestinations) {
        return new AirlineStats(groups, minPairs, minDestinations);
    }
}
