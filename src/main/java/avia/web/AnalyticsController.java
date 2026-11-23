package avia.web;

import avia.task2.analysis.PairedTransferStats;
import avia.task4.analysis.AirlineStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static avia.web.RequestParameters.*;

@RestController
@RequestMapping("/api/tasks")
public class AnalyticsController {
    private final PreparedAnalytics analytics;

    public AnalyticsController(PreparedAnalytics analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/1/results")
    public PreparedAnalytics.Seasonality seasonality(@RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination, @RequestParam(required = false) YearMonth month) {
        return analytics.seasonality(optionalIata(origin), optionalIata(destination), month);
    }

    @GetMapping("/2/results")
    public PreparedAnalytics.Transfers transfers(@RequestParam(required = false) String origin,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(defaultValue = "" + PairedTransferStats.DEFAULT_MIN_PAIRS) int minPairs,
            @RequestParam(defaultValue = "true") boolean allSnapshots) {
        return analytics.transfers(optionalIata(origin), month, positive(minPairs, "Минимум пар"), !allSnapshots);
    }

    @GetMapping("/3/results")
    public PreparedAnalytics.Delays delays(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) List<String> airports,
            @RequestParam(defaultValue = "10") int minSamples) {
        return analytics.delays(from, to, airports(airports, false), positive(minSamples, "Минимум наблюдений"));
    }

    @GetMapping("/4/results")
    public PreparedAnalytics.Airlines airlines(@RequestParam(required = false) String origin,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(defaultValue = "" + AirlineStats.DEFAULT_MIN_PAIRS) int minPairs,
            @RequestParam(defaultValue = "" + AirlineStats.DEFAULT_MIN_DESTINATIONS) int minDestinations,
            @RequestParam(defaultValue = "true") boolean directOnly,
            @RequestParam(defaultValue = "true") boolean allSnapshots) {
        return analytics.airlines(optionalIata(origin), month, directOnly, !allSnapshots,
                positive(minPairs, "Минимум сравнений"), positive(minDestinations, "Минимум направлений"));
    }
}
