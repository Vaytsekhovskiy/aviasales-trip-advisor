package avia.model;

import java.time.DayOfWeek;
import java.util.Objects;

public record FlightProfileKey(FlightServiceIdentity service, DayOfWeek weekday, int hour) {
    public FlightProfileKey {
        Objects.requireNonNull(service); Objects.requireNonNull(weekday);
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("Invalid hour");
    }
    @Override public String toString() {
        return String.join("|", service.airline(), service.number(), service.origin(), service.destination(), weekday.name(), Integer.toString(hour));
    }
}
