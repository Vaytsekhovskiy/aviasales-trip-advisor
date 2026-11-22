package avia.service;

import avia.model.*;
import avia.model.DelayDashboardData.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Views over already deduplicated FlightRecords. All delay formulas come from FlightHistoryAggregator. */
public final class DelayVisualizationAggregator {
    public static final int MIN_HEATMAP_SAMPLE = 10;
    public static final int MIN_AIRLINE_SAMPLE = 50;
    public static final int TOP_AIRLINES = 15;
    private final int heatmapMin, airlineMin;

    public DelayVisualizationAggregator() { this(MIN_HEATMAP_SAMPLE, MIN_AIRLINE_SAMPLE); }
    public DelayVisualizationAggregator(int heatmapMin, int airlineMin) {
        if (heatmapMin < 1 || airlineMin < 1) throw new IllegalArgumentException("Sample thresholds must be positive");
        this.heatmapMin = heatmapMin; this.airlineMin = airlineMin;
    }

    public DelayDashboardData aggregate(List<FlightRecord> flights) { return aggregate(flights, "MongoDB · avia.flights"); }

    public DelayDashboardData aggregate(List<FlightRecord> flights, String source) {
        List<FlightRecord> dated = flights.stream().filter(f -> f.scheduledDepartureLocal() != null).toList();
        Map<String, List<FlightRecord>> day = groups(dated, f -> f.scheduledDepartureLocal().getDayOfWeek().name());
        Map<String, List<FlightRecord>> hour = groups(dated, f -> f.scheduledDepartureLocal().getDayOfWeek().name() + "|" + f.scheduledDepartureLocal().getHour());
        List<Group> weekdays = new ArrayList<>(), heatmap = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            weekdays.add(summarize(d.name(), day.getOrDefault(d.name(), List.of())));
            for (int h = 0; h < 24; h++) {
                String key = d.name() + "|" + h;
                heatmap.add(summarize(key, hour.getOrDefault(key, List.of())));
            }
        }
        List<Group> airports = summaries(groups(flights, f -> known(f.originIata())));
        List<Group> allAirlines = summaries(groups(flights, f -> known(f.airlineIata())));
        List<Group> airlines = allAirlines.stream().filter(g -> !g.label().equals("Unknown") && g.departureN() >= airlineMin)
                .sorted(Comparator.comparingDouble((Group g) -> g.metrics().medianDepartureDelayMin()).reversed()
                        .thenComparing(Group::label)).limit(TOP_AIRLINES).toList();
        List<Group> daily = new ArrayList<>();
        var dates = groups(dated, f -> f.scheduledDepartureLocal().toLocalDate().toString());
        LocalDate from = dates.isEmpty() ? null : LocalDate.parse(dates.keySet().iterator().next());
        LocalDate to = dates.isEmpty() ? null : LocalDate.parse(dates.lastKey());
        if (from != null) for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            daily.add(summarize(date.toString(), dates.getOrDefault(date.toString(), List.of())));
        }
        List<Point> points = flights.stream().filter(f -> !f.cancelled() && f.departureDelayMin() != null && f.arrivalDelayMin() != null)
                .map(f -> new Point(f.departureDelayMin().doubleValue(), f.arrivalDelayMin().doubleValue())).toList();
        Group total = summarize("All", flights);
        long recovered = points.stream().filter(p -> p.departure() > p.arrival()).count();
        long worsened = points.stream().filter(p -> p.departure() < p.arrival()).count();
        List<Double> coordinates = new ArrayList<>();
        for (Point p : points) { coordinates.add(p.departure()); coordinates.add(p.arrival()); }
        coordinates.sort(Double::compareTo);
        double low = coordinates.isEmpty() ? 0 : Math.min(0, coordinates.get((int) Math.floor((coordinates.size() - 1) * .01)));
        double high = coordinates.isEmpty() ? 1 : Math.max(1, coordinates.get((int) Math.ceil((coordinates.size() - 1) * .99)));
        Recovery recovery = new Recovery(points, total.metrics().medianDelayRecoveryMin(), fraction(recovered, points.size()),
                fraction(worsened, points.size()), fraction(points.size() - recovered - worsened, points.size()), low, high);
        return new DelayDashboardData(source, total,
                (int) airports.stream().filter(g -> !g.label().equals("Unknown")).count(),
                (int) allAirlines.stream().filter(g -> !g.label().equals("Unknown")).count(), from, to,
                flights.size() - dated.size(), heatmapMin, airlineMin, List.copyOf(weekdays), List.copyOf(heatmap),
                airports, airlines, List.copyOf(daily), recovery);
    }

    private static String known(String value) { return value == null || value.isBlank() ? "Unknown" : value; }
    private static Double fraction(long n, int size) { return size == 0 ? null : n / (double) size; }
    private static TreeMap<String, List<FlightRecord>> groups(List<FlightRecord> flights, Function<FlightRecord, String> key) {
        TreeMap<String, List<FlightRecord>> result = new TreeMap<>();
        for (FlightRecord f : flights) result.computeIfAbsent(key.apply(f), ignored -> new ArrayList<>()).add(f);
        return result;
    }
    private static List<Group> summaries(Map<String, List<FlightRecord>> groups) {
        return groups.entrySet().stream().map(e -> summarize(e.getKey(), e.getValue())).toList();
    }
    private static Group summarize(String label, List<FlightRecord> flights) {
        return new Group(label, (int) flights.stream().filter(f -> !f.cancelled() && f.departureDelayMin() != null).count(),
                (int) flights.stream().filter(f -> !f.cancelled() && f.arrivalDelayMin() != null).count(),
                FlightHistoryAggregator.summarizeObservations(label, flights));
    }
}
