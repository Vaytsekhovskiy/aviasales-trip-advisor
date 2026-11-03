package avia.service;

import avia.model.FlightBatch;
import avia.storage.FlightStorage;
import java.io.IOException;
import java.time.Instant;

/** Orchestrates batches without knowing their physical storage location. */
public final class FlightCollector {
    @FunctionalInterface
    public interface Source {
        CollectedJson fetch(FlightBatch batch) throws IOException, InterruptedException;
    }

    public record CollectedJson(String json, Instant collectedAt) { }
    private final Source source;
    private final FlightPreprocessor preprocessor;
    private final FlightStorage storage;

    public FlightCollector(Source source, FlightPreprocessor preprocessor, FlightStorage storage) {
        this.source = source;
        this.preprocessor = preprocessor;
        this.storage = storage;
    }

    /** Returns -1 for an existing batch, otherwise the count saved (including zero). */
    public int collect(FlightBatch batch) throws IOException, InterruptedException {
        if (storage.exists(batch)) return -1;
        CollectedJson response = source.fetch(batch);
        var flights = preprocessor.process(response.json(), batch, response.collectedAt());
        storage.save(flights, batch);
        return flights.size();
    }
}
