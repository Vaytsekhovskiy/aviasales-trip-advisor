package avia.web;

import avia.common.flight.model.FlightRecord;
import avia.common.flight.mongo.MongoFlightReader;
import avia.common.price.mongo.MongoSource;
import avia.task1.analysis.CheapestDestinationAggregator;
import avia.task1.analysis.SeasonalityAggregator;
import avia.task1.model.PriceEntry;
import avia.task1.service.Task1Service;
import avia.task2.analysis.PairedTransferStats;
import avia.task2.analysis.TransferStats;
import avia.task2.service.Task2Service;
import avia.task3.analysis.DelayAnalyzer;
import avia.task3.model.DelayDashboardData;
import avia.task3.service.Task3Service;
import avia.task4.analysis.AirlineStats;
import avia.task4.service.Task4Service;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class PreparedAnalytics {
    private static final String PRICES = "prices_for_dates";
    private static final String CALENDAR = "calendar";

    private final MongoDatabase database;
    private final Clock clock;
    private final Task1Service task1;
    private final Task2Service task2;
    private final Task3Service task3;
    private final Task4Service task4;

    public PreparedAnalytics(@Lazy MongoDatabase database, Clock clock, Task1Service task1, Task2Service task2,
                             Task3Service task3, Task4Service task4) {
        this.database = database;
        this.clock = clock;
        this.task1 = task1;
        this.task2 = task2;
        this.task3 = task3;
        this.task4 = task4;
    }

    public record SeasonCell(int month, DayOfWeek weekday, SeasonalityAggregator.Cell price) { }
    public record CheapestCell(int month, DayOfWeek weekday, CheapestDestinationAggregator.Cell price) { }
    public record Seasonality(String source, String origin, String destination, List<String> origins,
                              List<String> destinations, MongoSource.Snapshots snapshots,
                              int sampleCount, int cheapestSampleCount,
                              List<SeasonCell> seasonality, List<CheapestCell> cheapest) { }

    public Seasonality seasonality(String requestedOrigin, String destination, YearMonth month) {
        var source = new MongoSource(database);
        List<String> origins = Stream.concat(source.loadOrigins(CALENDAR).stream(), source.loadOrigins(PRICES).stream())
                .distinct().sorted().toList();
        String origin = selectOrigin(requestedOrigin, origins);
        String dataset = CALENDAR;
        List<PriceEntry> entries = origin == null ? List.of() : source.loadSeasonality(dataset, origin, month);
        if (origin != null && (entries.isEmpty()
                || destination != null && entries.stream().noneMatch(entry -> destination.equals(entry.destination())))) {
            List<PriceEntry> alternative = source.loadSeasonality(PRICES, origin, month);
            if (!alternative.isEmpty() && (destination == null
                    || alternative.stream().anyMatch(entry -> destination.equals(entry.destination())))) {
                dataset = PRICES;
                entries = alternative;
            }
        }

        List<String> destinations = Stream.concat(source.destinations(CALENDAR, origin).stream(),
                        source.destinations(PRICES, origin).stream()).distinct().sorted().toList();
        List<PriceEntry> seasonEntries = destination == null ? entries : entries.stream()
                .filter(entry -> destination.equals(entry.destination())).toList();
        var season = task1.analyzeSeasonality(seasonEntries);
        var cheap = task1.findCheapestDestinations(entries);
        List<SeasonCell> seasonality = IntStream.rangeClosed(1, 12).boxed()
                .flatMap(m -> Arrays.stream(DayOfWeek.values()).map(d -> new SeasonCell(m, d, season.get(m, d)))).toList();
        List<CheapestCell> cheapest = IntStream.rangeClosed(1, 12).boxed()
                .flatMap(m -> Arrays.stream(DayOfWeek.values()).map(d -> new CheapestCell(m, d, cheap.get(m, d)))).toList();
        return new Seasonality(dataset, origin, destination, origins, destinations, source.snapshots(dataset, origin, month),
                seasonEntries.size(), entries.size(), seasonality, cheapest);
    }

    public record Transfers(String origin, List<String> origins, MongoSource.Snapshots snapshots,
                            int[] offerCounts, boolean perSnapshot, int minPairs,
                            PairedTransferStats.Overall oneStop, PairedTransferStats.Overall twoPlus,
                            List<PairedTransferStats.Destination> pairs,
                            List<TransferStats.Destination> prices) { }

    public Transfers transfers(String requestedOrigin, YearMonth month, int minPairs, boolean perSnapshot) {
        var source = new MongoSource(database);
        List<String> origins = source.loadOrigins();
        String origin = selectOrigin(requestedOrigin, origins);
        var data = origin == null ? new MongoSource.TransferData(Map.of(), new int[3], Map.of())
                : source.loadTransferData(origin, perSnapshot, month);
        var pairs = task2.compareMatchedDates(data.minsByDestination(), minPairs);
        var prices = task2.summarize(data.pricesByDestination());
        return new Transfers(origin, origins, source.snapshots(PRICES, origin, month), data.ticketsByBucket(),
                perSnapshot, minPairs, pairs.overall(1), pairs.overall(2),
                pairs.destinations().stream().sorted(Comparator.comparing(PairedTransferStats.Destination::code)).toList(),
                prices.destinations().stream().sorted(Comparator.comparing(TransferStats.Destination::code)).toList());
    }

    public record Delays(LocalDate from, LocalDate to, List<String> airports, List<String> selectedAirports,
                         int minSamples, int flightCount, DelayDashboardData.Group total,
                         List<DelayDashboardData.Group> weekdays, List<DelayDashboardData.Group> daily,
                         List<DelayDashboardData.Group> heatmap,
                         Map<String, DelayAnalyzer.Stats> byAirport, Map<String, DelayAnalyzer.Stats> byAirline,
                         Map<String, DelayAnalyzer.Stats> byRoute) { }

    public Delays delays(LocalDate from, LocalDate to, List<String> requestedAirports, int minSamples) {
        List<FlightRecord> history = new MongoFlightReader(database)
                .read(Filters.lt("scheduledDepartureUtc", Date.from(clock.instant())));
        List<String> airports = history.stream().map(FlightRecord::originIata)
                .filter(code -> code != null && !code.isBlank()).distinct().sorted().toList();
        LocalDate today = LocalDate.now(clock);
        var dates = history.stream().filter(flight -> flight.scheduledDepartureLocal() != null)
                .map(flight -> flight.scheduledDepartureLocal().toLocalDate()).sorted().toList();
        LocalDate start = from != null ? from : dates.isEmpty() ? today : dates.get(0);
        LocalDate end = to != null ? to : dates.isEmpty() ? today : dates.get(dates.size() - 1);
        if (end.isBefore(start)) throw RequestParameters.invalid("Дата окончания должна быть не раньше даты начала.");
        List<String> selectedAirports = requestedAirports.isEmpty() ? airports : requestedAirports;
        var result = task3.analyze(history, start, end, selectedAirports, minSamples);
        var dashboard = task3.buildDashboard(result.flights());
        return new Delays(start, end, airports, selectedAirports, minSamples, result.flights().size(),
                dashboard.total(), dashboard.weekdays(), dashboard.daily(), dashboard.heatmap(),
                result.byAirport(), result.byAirline(), result.byRoute());
    }

    public record RouteAirline(String destination, String airline, AirlineStats.Cell price) { }
    public record Airlines(String origin, List<String> origins, MongoSource.Snapshots snapshots,
                           int groups, int minPairs, int minDestinations, boolean directOnly, boolean perSnapshot,
                           List<AirlineStats.Airline> airlines, List<RouteAirline> routes) { }

    public Airlines airlines(String requestedOrigin, YearMonth month, boolean directOnly, boolean perSnapshot,
                             int minPairs, int minDestinations) {
        var source = new MongoSource(database);
        List<String> origins = source.loadOrigins();
        String origin = selectOrigin(requestedOrigin, origins);
        var groups = origin == null ? List.<AirlineStats.Group>of()
                : source.loadAirlineGroups(origin, directOnly, perSnapshot, month);
        var stats = task4.compare(groups, minPairs, minDestinations);
        List<RouteAirline> routes = stats.destinationsByComparisons().stream()
                .flatMap(destination -> stats.airlines().stream()
                        .map(airline -> new RouteAirline(destination, airline.code(), stats.cell(destination, airline.code()))))
                .filter(route -> route.price() != null).toList();
        return new Airlines(origin, origins, source.snapshots(PRICES, origin, month), stats.totalGroups(),
                minPairs, minDestinations, directOnly, perSnapshot, stats.airlines(), routes);
    }

    private static String selectOrigin(String requested, List<String> available) {
        return requested != null || available.isEmpty() ? requested : available.get(0);
    }
}
