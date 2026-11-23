package avia.web;

import avia.task5.model.FlightPredictionRequest;
import avia.task5.service.FutureFlightCatalog;
import avia.task5.service.JointPredictionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/tasks/5")
class PredictionController {
    private final FutureFlightCatalog catalog;
    private final JointPredictionService predictions;
    private final Clock clock;

    PredictionController(FutureFlightCatalog catalog, JointPredictionService predictions, Clock clock) {
        this.catalog = catalog;
        this.predictions = predictions;
        this.clock = clock;
    }

    @GetMapping("/flights")
    FutureFlightCatalog.Page flights(@RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination, @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "1") int page) {
        return catalog.list(clock.instant(), RequestParameters.optionalIata(origin),
                RequestParameters.optionalIata(destination), date, RequestParameters.positive(page, "Номер страницы"));
    }

    @PostMapping("/predict")
    JointPredictionService.Prediction predict(@RequestBody FlightPredictionRequest request) {
        return predictions.predict(request);
    }
}
