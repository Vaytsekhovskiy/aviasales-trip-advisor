package avia.task5.price.model;

public record FlightPredictionRequest(
        String airlineIata,
        String flightNumber,
        String originIata,
        String destinationIata,
        String scheduledDeparture,
        String predictionTimeUtc
) {}
