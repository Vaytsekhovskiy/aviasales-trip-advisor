package avia.model;

public record FlightPredictionRequest(
        String airlineIata,
        String flightNumber,
        String originIata,
        String destinationIata,
        String scheduledDeparture,
        String predictionTimeUtc
) {}
