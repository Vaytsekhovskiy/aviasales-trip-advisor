package avia.task5.price.service;

import avia.common.config.AviaProperties;
import avia.task5.model.FlightPredictionRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class Task5PricePredictionService {
    private final Path modelPath;
    private PricePredictionService predictor;

    public Task5PricePredictionService(AviaProperties properties) {
        modelPath = properties.models().pricePath();
    }

    public float predict(FlightPredictionRequest request) throws Exception {
        return session().predictPrice(request);
    }

    private synchronized PricePredictionService session() throws Exception {
        if (predictor == null) predictor = new PricePredictionService(modelPath);
        return predictor;
    }

    @PreDestroy
    synchronized void close() throws Exception {
        if (predictor != null) predictor.close();
    }
}
