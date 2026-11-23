package avia.task5.service;

import avia.common.aviasales.mongo.MongoAviasalesPriceStorage;
import avia.common.flight.model.FlightServiceIdentity;
import avia.task5.delay.model.FlightProfileKey;
import avia.task5.delay.mongo.MongoPredictableFlightProfileStorage;
import avia.task5.delay.service.FlightProfileAggregator;
import avia.task5.model.FlightPredictionRequest;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class FutureFlightCatalog {
    private static final int PAGE_SIZE = 100;
    private static final String DEPARTURE = "departure_at";
    private static final Bson FIELDS = Projections.include("airline", "flight_number", "origin_airport",
            "destination_airport", DEPARTURE);

    private final MongoDatabase database;

    public FutureFlightCatalog(@Lazy MongoDatabase database) {
        this.database = database;
    }

    public record Flight(String airlineIata, String flightNumber, String originIata, String destinationIata,
                         String scheduledDeparture, String profileKey, long delayHistoryFlights,
                         long priceHistoryFlights) {
        FlightPredictionRequest request(Instant now) {
            return new FlightPredictionRequest(airlineIata, flightNumber, originIata, destinationIata,
                    scheduledDeparture, now.toString());
        }
    }

    public record Page(int page, int pageSize, int total, int profileCount, List<Flight> flights) { }
    private record Support(long delay, long price) { }

    public Page list(Instant now, String origin, String destination, LocalDate departureDate, int page) {
        Map<String, Support> supported = new HashMap<>();
        for (var profile : profiles().find().projection(Projections.include("delayHistoryFlights", "priceHistoryFlights"))) {
            supported.put(profile.getString("_id"), support(profile));
        }
        Set<Flight> eligible = new HashSet<>();
        if (!supported.isEmpty()) {
            for (var document : prices().find(filter(now, origin, destination, departureDate)).projection(FIELDS)) {
                FlightProfileAggregator.priceFlight(document).filter(flight -> flight.departure().toInstant().isAfter(now))
                        .ifPresent(flight -> {
                            Support history = supported.get(flight.profile().toString());
                            if (history != null) eligible.add(flight(flight, history));
                        });
            }
        }
        List<Flight> sorted = eligible.stream()
                .sorted(Comparator.comparing((Flight f) -> OffsetDateTime.parse(f.scheduledDeparture()).toInstant())
                        .thenComparing(Flight::profileKey).thenComparing(Flight::scheduledDeparture))
                .toList();
        List<Flight> result = sorted.stream().skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).toList();
        return new Page(page, PAGE_SIZE, sorted.size(), supported.size(), result);
    }

    public Optional<Flight> find(FlightServiceIdentity identity, OffsetDateTime departure, Instant now) {
        if (!departure.toInstant().isAfter(now)) return Optional.empty();
        var key = new FlightProfileKey(identity, departure.getDayOfWeek(), departure.getHour());
        var profile = profiles().find(Filters.eq("_id", key.toString())).first();
        if (profile == null) return Optional.empty();
        var candidate = new FlightProfileAggregator.PriceFlight(key, departure);
        Bson filter = filter(now, identity.origin(), identity.destination(), departure.toLocalDate());
        try (var cursor = prices().find(filter).projection(FIELDS).iterator()) {
            while (cursor.hasNext()) {
                if (FlightProfileAggregator.priceFlight(cursor.next()).filter(candidate::equals).isPresent()) {
                    return Optional.of(flight(candidate, support(profile)));
                }
            }
        }
        return Optional.empty();
    }

    private static Bson filter(Instant now, String origin, String destination, LocalDate date) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.in("transfers", 0, "0"));
        if (origin != null) filters.add(Filters.eq("origin_airport", origin));
        if (destination != null) filters.add(Filters.eq("destination_airport", destination));
        if (date == null) {
            // A local departure date can be yesterday relative to UTC. Compare exact instants after parsing.
            filters.add(Filters.gte(DEPARTURE, now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(1).toString()));
        } else {
            filters.add(Filters.gte(DEPARTURE, date.toString()));
            filters.add(Filters.lt(DEPARTURE, date.plusDays(1).toString()));
        }
        return Filters.and(filters);
    }

    private MongoCollection<Document> prices() {
        return database.getCollection(MongoAviasalesPriceStorage.COLLECTION);
    }

    private MongoCollection<Document> profiles() {
        return database.getCollection(MongoPredictableFlightProfileStorage.COLLECTION);
    }

    private static Support support(Document profile) {
        return new Support(number(profile, "delayHistoryFlights"), number(profile, "priceHistoryFlights"));
    }

    private static long number(Document document, String key) {
        return document.get(key) instanceof Number number ? number.longValue() : 0;
    }

    private static Flight flight(FlightProfileAggregator.PriceFlight flight, Support support) {
        var key = flight.profile();
        var identity = key.service();
        return new Flight(identity.airline(), identity.number(), identity.origin(), identity.destination(),
                flight.departure().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                key.toString(), support.delay(), support.price());
    }
}
