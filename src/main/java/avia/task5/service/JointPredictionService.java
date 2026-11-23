package avia.task5.service;

import avia.common.flight.model.FlightServiceIdentity;
import avia.task5.delay.service.DelayModelClient;
import avia.task5.model.FlightPredictionRequest;
import avia.task5.price.service.Task5PricePredictionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class JointPredictionService {
    private static final Logger LOG = LoggerFactory.getLogger(JointPredictionService.class);
    private final FutureFlightCatalog catalog;
    private final Task5PricePredictionService priceModel;
    private final DelayModelClient delayModel;
    private final Clock clock;

    public JointPredictionService(FutureFlightCatalog catalog, Task5PricePredictionService priceModel,
                                  DelayModelClient delayModel, Clock clock) {
        this.catalog = catalog;
        this.priceModel = priceModel;
        this.delayModel = delayModel;
        this.clock = clock;
    }

    public record Price(double predictedPrice, String currency) { }
    public record Prediction(FlightPredictionRequest flight, Price price, DelayModelClient.Prediction delay) { }

    public Prediction predict(FlightPredictionRequest selection) {
        Instant now = clock.instant();
        FlightServiceIdentity identity;
        OffsetDateTime departure;
        try {
            identity = new FlightServiceIdentity(selection.airlineIata(), selection.flightNumber(),
                    selection.originIata(), selection.destinationIata());
            departure = OffsetDateTime.parse(selection.scheduledDeparture());
        } catch (DateTimeException | IllegalArgumentException error) {
            throw new ResponseStatusException(BAD_REQUEST, "Проверьте коды рейса и время вылета.");
        }
        var available = catalog.find(identity, departure, now).orElseThrow(() -> new ResponseStatusException(
                CONFLICT, "Этот вылет сейчас недоступен для общего прогноза. Обновите список рейсов."));
        FlightPredictionRequest request = available.request(now);
        float price;
        try {
            price = priceModel.predict(request);
            if (!Float.isFinite(price)) throw new IllegalStateException("Non-finite price model output");
        } catch (Exception error) {
            LOG.warn("ONNX prediction failed ({})", error.getClass().getSimpleName());
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Модель цены недоступна. Проверьте файл price_model.onnx.");
        }
        var delay = delayModel.predict(request);
        return new Prediction(request, new Price(price, "RUB"), delay);
    }
}
