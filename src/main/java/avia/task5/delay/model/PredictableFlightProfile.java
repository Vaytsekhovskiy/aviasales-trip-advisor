package avia.task5.delay.model;

public record PredictableFlightProfile(FlightProfileKey profile, long delayHistoryFlights, long priceHistoryFlights) {
    public String profileKey() { return profile.toString(); }
    public boolean passes(long delay, long price) { return delayHistoryFlights >= delay && priceHistoryFlights >= price; }
}
