package avia.service;

import avia.model.*;
import org.bson.Document;
import java.time.*;
import java.util.*;

public final class FlightProfileAggregator {
    public record PriceFlight(FlightProfileKey profile, OffsetDateTime departure) { }
    public static Optional<PriceFlight> priceFlight(Document d) {
        try {
            var identity = new FlightServiceIdentity(d.getString("airline"), d.getString("flight_number"),
                    d.getString("origin_airport"), d.getString("destination_airport"));
            var departure = OffsetDateTime.parse(d.getString("departure_at"));
            return Optional.of(new PriceFlight(new FlightProfileKey(identity, departure.getDayOfWeek(), departure.getHour()), departure));
        } catch (IllegalArgumentException | DateTimeException | NullPointerException | ClassCastException invalid) {
            return Optional.empty();
        }
    }
    public Map<FlightProfileKey, Long> delayCounts(Collection<FlightRecord> records) {
        Map<String, FlightRecord> latest = new HashMap<>();
        for (var f : records) if (f.flightId() != null && !f.flightId().isBlank())
            latest.merge(f.flightId(), f, (a,b) -> FlightObservationOrder.COMPARATOR.compare(a,b) >= 0 ? a : b);
        Map<FlightProfileKey, Long> counts = new HashMap<>();
        for (var f : latest.values()) {
            if (f.cancelled() || f.departureDelayMin() == null || f.departureWeekday() == null || f.departureHour() == null) continue;
            try {
                var key = new FlightProfileKey(new FlightServiceIdentity(f.airlineIata(), f.flightNumber(), f.originIata(), f.destinationIata()), f.departureWeekday(), f.departureHour());
                counts.merge(key, 1L, Long::sum);
            } catch (IllegalArgumentException invalid) { /* Incomplete service identity. */ }
        }
        return counts;
    }
    public Map<FlightProfileKey, Long> priceCounts(Collection<PriceFlight> flights) {
        Map<FlightProfileKey, Long> counts = new HashMap<>();
        new HashSet<>(flights).forEach(f -> counts.merge(f.profile(), 1L, Long::sum));
        return counts;
    }
}
