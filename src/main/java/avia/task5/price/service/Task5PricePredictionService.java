package avia.task5.price.service;

import avia.task5.price.model.FlightPredictionRequest;
import org.springframework.stereotype.Service;

/** Lazy ONNX application boundary: the absent model does not prevent Spring from starting. */
@Service
public class Task5PricePredictionService {
    public float predict(FlightPredictionRequest request) throws Exception {
        try (PricePredictionService predictor = new PricePredictionService()) {
            return predictor.predictPrice(request);
        }
    }
}
