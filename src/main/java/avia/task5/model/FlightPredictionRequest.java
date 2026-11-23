package avia.task5.model;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Common public request contract for both Task 5 price and delay prediction. */
public record FlightPredictionRequest(
        String airlineIata,
        String flightNumber,
        String originIata,
        String destinationIata,
        String scheduledDeparture,
        String predictionTimeUtc
) {
    public FlightPredictionRequest {
        Objects.requireNonNull(airlineIata, "airlineIata is required");
        Objects.requireNonNull(flightNumber, "flightNumber is required");
        Objects.requireNonNull(originIata, "originIata is required");
        Objects.requireNonNull(destinationIata, "destinationIata is required");
        Objects.requireNonNull(scheduledDeparture, "scheduledDeparture is required");
        Objects.requireNonNull(predictionTimeUtc, "predictionTimeUtc is required");

        OffsetDateTime departure = OffsetDateTime.parse(scheduledDeparture);
        OffsetDateTime predictionTime = OffsetDateTime.parse(predictionTimeUtc);
        if (!predictionTime.getOffset().equals(ZoneOffset.UTC)) {
            throw new IllegalArgumentException("predictionTimeUtc must be a UTC timestamp (Z or +00:00)");
        }
        if (!predictionTime.toInstant().isBefore(departure.toInstant())) {
            throw new IllegalArgumentException("predictionTimeUtc must be earlier than scheduledDeparture");
        }
    }
}
