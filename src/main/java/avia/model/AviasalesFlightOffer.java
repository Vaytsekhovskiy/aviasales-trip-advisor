package avia.model;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;

/** A normalized prices_for_dates offer. Airport codes, not city codes, identify the service. */
public record AviasalesFlightOffer(String airlineIata, String flightNumber,
        String originAirport, String destinationAirport, OffsetDateTime departureAt,
        long price, String currency, int transfers, int durationMin) {
    public AviasalesFlightOffer {
        airlineIata = compact(airlineIata);
        originAirport = compact(originAirport);
        destinationAirport = compact(destinationAirport);
        flightNumber = compact(flightNumber);
        if (!airlineIata.matches("[A-Z0-9]{2}") || !originAirport.matches("[A-Z]{3}")
                || !destinationAirport.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid IATA code");
        }
        // Strip an airline prefix only when the remaining part is itself a flight number.
        if (flightNumber.startsWith(airlineIata)
                && flightNumber.substring(airlineIata.length()).matches("[0-9]+[A-Z]?")) {
            flightNumber = flightNumber.substring(airlineIata.length());
        }
        if (!flightNumber.matches("[0-9]+[A-Z]?")) throw new IllegalArgumentException("Invalid flight number");
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
