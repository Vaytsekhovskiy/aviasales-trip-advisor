package avia.common.flight.model;

/** Shared Aviasales-compatible service identity normalization. */
public record FlightServiceIdentity(String airline, String number, String origin, String destination) {
    public FlightServiceIdentity {
        airline = compact(airline); number = compact(number);
        origin = compact(origin); destination = compact(destination);
        if (!airline.matches("[A-Z0-9]{2}") || !origin.matches("[A-Z]{3}") || !destination.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Invalid IATA code");
        if (number.startsWith(airline) && number.substring(airline.length()).matches("[0-9]+[A-Z]?"))
            number = number.substring(airline.length());
        if (!number.matches("[0-9]+[A-Z]?")) throw new IllegalArgumentException("Invalid flight number");
    }
    public static String compact(String value) {
        if (value == null) throw new IllegalArgumentException("Missing field");
        return value.replaceAll("(?U)\\s+", "").toUpperCase(java.util.Locale.ROOT);
    }
}
