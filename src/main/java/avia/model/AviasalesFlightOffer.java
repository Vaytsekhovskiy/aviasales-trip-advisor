package avia.model;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;

/** A normalized prices_for_dates offer. Airport codes, not city codes, identify the service. */
public record AviasalesFlightOffer(String airlineIata, String flightNumber,
        String originAirport, String destinationAirport, OffsetDateTime departureAt,
        long price, String currency, int transfers, int durationMin) {
    public AviasalesFlightOffer {
        var identity = new FlightServiceIdentity(airlineIata, flightNumber, originAirport, destinationAirport);
        airlineIata = identity.airline(); flightNumber = identity.number();
        originAirport = identity.origin(); destinationAirport = identity.destination();
        currency = compact(currency);
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        Objects.requireNonNull(departureAt, "departureAt");
        if (price < 0 || transfers < 0 || durationMin < 0) throw new IllegalArgumentException("Negative offer value");
    }

    public String flightServiceKey() {
        return airlineIata + "|" + flightNumber + "|" + originAirport + "|" + destinationAirport;
    }

    private static String compact(String value) {
        if (value == null) throw new IllegalArgumentException("Missing offer field");
        return value.replaceAll("(?U)\\s+", "").toUpperCase(Locale.ROOT);
    }
}

