package avia.service;

import avia.model.FlightBatch;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class FlightDatasetPlan {
    public static final List<String> AIRPORTS = List.of("SVO", "LED", "AER", "OVB", "SVX");
    public static final Map<String, String> ICAO = Map.of("SVO", "UUEE", "LED", "ULLI", "AER", "URSS", "OVB", "UNNT", "SVX", "USSS");

    public static List<FlightBatch> batches(LocalDate from, LocalDate to, List<String> airports) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365) throw new IllegalArgumentException("Date range must cover 1 to 366 days");
        if (airports.isEmpty() || new HashSet<>(airports).size() != airports.size()) throw new IllegalArgumentException("Airports must be nonempty and unique");
        List<FlightBatch> batches = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (String airport : airports) {
                batches.add(new FlightBatch(airport, date, LocalTime.MIDNIGHT, LocalTime.NOON));
                batches.add(new FlightBatch(airport, date, LocalTime.NOON, LocalTime.MIDNIGHT));
            }
        }
        return List.copyOf(batches);
    }
}
