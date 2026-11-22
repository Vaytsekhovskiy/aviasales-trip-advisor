package avia.model;

/** Per-service statistics after deduplication. Undefined delay metrics/rates are null.
 * Sample size includes cancelled flights; recovery is departure minus arrival delay.
 */
public record FlightHistoricalStats(
        String flightServiceKey,
        int sampleSize,
        Double medianDepartureDelayMin,
        Double meanDepartureDelayMin,
        Double medianArrivalDelayMin,
        Double meanArrivalDelayMin,
        Double departureDelay15Rate,
        Double departureDelay30Rate,
        Double departureDelay60Rate,
        double cancellationRate,
        Double medianDelayRecoveryMin) {
}
