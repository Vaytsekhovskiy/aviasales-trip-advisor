package avia.task1.service;

import avia.task1.analysis.CheapestDestinationAggregator;
import avia.task1.analysis.SeasonalityAggregator;
import avia.task1.model.PriceEntry;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application boundary for Task 1; data acquisition and rendering stay separate. */
@Service
public class Task1Service {
    public SeasonalityAggregator analyzeSeasonality(List<PriceEntry> prices) {
        return new SeasonalityAggregator(prices);
    }

    public CheapestDestinationAggregator findCheapestDestinations(List<PriceEntry> prices) {
        return new CheapestDestinationAggregator(prices);
    }
}
