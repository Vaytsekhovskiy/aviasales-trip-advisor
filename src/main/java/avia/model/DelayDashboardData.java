package avia.model;

import java.time.LocalDate;
import java.util.List;

/** Precomputed visualization data; N is metric-specific, never inferred from total flights. */
public record DelayDashboardData(
        String source, Group total, int airportCount, int airlineCount,
        LocalDate from, LocalDate to, int missingLocalDeparture,
        int minHeatmapSample, int minAirlineSample,
        List<Group> weekdays, List<Group> heatmap, List<Group> airports,
        List<Group> airlines, List<Group> daily, Recovery recovery) {
    public record Group(String label, int departureN, int arrivalN, FlightHistoricalStats metrics) { }
    public record Point(double departure, double arrival) { }
    public record Recovery(List<Point> points, Double median, Double recoveredShare, Double worsenedShare,
                           Double unchangedShare, double centralLow, double centralHigh) { }
}
