package avia.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

/** A single airport FIDS request window. An end value of 00:00 means 24:00 of the batch date. */
public record FlightBatch(String airport, LocalDate date, LocalTime from, LocalTime to) {

    public FlightBatch {
        Objects.requireNonNull(airport, "airport");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        airport = airport.trim().toUpperCase(Locale.ROOT);
        if (!airport.matches("[A-Z0-9]{3,4}")) {
            throw new IllegalArgumentException("Airport must be a 3- or 4-character IATA/ICAO code");
        }
        long hours = (to.equals(LocalTime.MIDNIGHT) ? 24 : to.getHour()) - from.getHour();
        if (from.getMinute() != 0 || to.getMinute() != 0 || from.getSecond() != 0
                || to.getSecond() != 0 || from.getNano() != 0 || to.getNano() != 0
                || hours <= 0 || hours > 12) {
            throw new IllegalArgumentException("Batch must cover 1 to 12 whole hours within one local day");
        }
    }

    public String fileName() {
        return "%s_%s-%s.csv".formatted(airport, hourLabel(from, false), hourLabel(to, true));
    }

    public LocalDateTime fromLocal() {
        return date.atTime(from);
    }

    public LocalDateTime toLocal() {
        return to.equals(LocalTime.MIDNIGHT) ? date.plusDays(1).atStartOfDay() : date.atTime(to);
    }

    private static String hourLabel(LocalTime time, boolean end) {
        if (end && time.equals(LocalTime.MIDNIGHT)) {
            return "24";
        }
        return "%02d".formatted(time.getHour());
    }
}
