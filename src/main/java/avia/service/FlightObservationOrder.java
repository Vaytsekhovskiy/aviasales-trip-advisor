package avia.service;

import avia.model.FlightRecord;
import java.util.Comparator;

/** Shared latest-observation policy. Select a whole row, never merge its fields. */
public final class FlightObservationOrder {
    private FlightObservationOrder() { }
    public static final Comparator<FlightRecord> COMPARATOR = Comparator
            .comparing(FlightRecord::collectedAtUtc, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingInt(f -> (f.departureDelayMin() != null ? 1 : 0)
                    + (f.arrivalDelayMin() != null ? 1 : 0)
                    + (f.scheduledDepartureLocal() != null ? 1 : 0))
            .thenComparing(f -> "departure".equals(f.direction()) ? 1 : 0)
            .thenComparing(FlightRecord::toString);
}
