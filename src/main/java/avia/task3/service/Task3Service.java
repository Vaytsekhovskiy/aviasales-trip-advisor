package avia.task3.service;

import avia.common.flight.model.FlightHistoricalStats;
import avia.common.flight.model.FlightRecord;
import avia.task3.analysis.DelayAnalyzer;
import avia.task3.analysis.DelayVisualizationAggregator;
import avia.task3.analysis.FlightHistoryAggregator;
import avia.task3.model.DelayDashboardData;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Descriptive delay-analysis boundary. This service has no prediction or ML dependency. */
@Service
public class Task3Service {
    private final DelayAnalyzer delayAnalyzer = new DelayAnalyzer();
    private final FlightHistoryAggregator historyAggregator = new FlightHistoryAggregator();
    private final DelayVisualizationAggregator visualizationAggregator = new DelayVisualizationAggregator();

    public DelayAnalyzer.Result analyze(List<FlightRecord> flights, LocalDate from, LocalDate to,
                                        List<String> airports, int minSamples) {
        return delayAnalyzer.analyze(flights, from, to, airports, minSamples);
    }

    public Map<String, FlightHistoricalStats> aggregateHistory(List<FlightRecord> flights) {
        return historyAggregator.aggregate(flights);
    }

    public DelayDashboardData buildDashboard(List<FlightRecord> flights) {
        return visualizationAggregator.aggregate(flights);
    }
}
