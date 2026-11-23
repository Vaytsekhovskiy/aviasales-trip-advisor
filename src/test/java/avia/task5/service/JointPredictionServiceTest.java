package avia.task5.service;

import avia.common.flight.model.FlightServiceIdentity;
import avia.task5.delay.service.DelayModelClient;
import avia.task5.model.FlightPredictionRequest;
import avia.task5.price.service.Task5PricePredictionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.CONFLICT;

class JointPredictionServiceTest {
    private final Instant now = Instant.parse("2026-11-15T12:00:00Z");
    private final String departure = "2026-11-21T13:20:00+03:00";
    private final FutureFlightCatalog catalog = mock(FutureFlightCatalog.class);
    private final Task5PricePredictionService price = mock(Task5PricePredictionService.class);
    private final DelayModelClient delay = mock(DelayModelClient.class);
    private final JointPredictionService service = new JointPredictionService(catalog, price, delay,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void bothModelsReceiveTheExactSameCatalogFlightWithServerTime() throws Exception {
        var flight = new FutureFlightCatalog.Flight("S7", "5104", "AER", "OVB", departure,
                "S7|5104|AER|OVB|SATURDAY|13", 8, 12);
        when(catalog.find(new FlightServiceIdentity("S7", "5104", "AER", "OVB"),
                OffsetDateTime.parse(departure), now)).thenReturn(Optional.of(flight));
        when(price.predict(any())).thenReturn(5420f);
        when(delay.predict(any())).thenReturn(new DelayModelClient.Prediction(60.8, "delay-v2", null));

        var prediction = service.predict(selection());

        var request = ArgumentCaptor.forClass(FlightPredictionRequest.class);
        verify(price).predict(request.capture());
        verify(delay).predict(same(request.getValue()));
        assertSame(request.getValue(), prediction.flight());
        assertEquals(departure, prediction.flight().scheduledDeparture());
        assertEquals(now.toString(), prediction.flight().predictionTimeUtc());
        assertEquals(new JointPredictionService.Price(5420, "RUB"), prediction.price());
        assertEquals(60.8, prediction.delay().predictedDelayMin());
    }

    @Test
    void rejectsAnUnlistedOccurrenceBeforeEitherModelRuns() {
        var error = assertThrows(ResponseStatusException.class, () -> service.predict(selection()));
        assertEquals(CONFLICT, error.getStatusCode());
        verifyNoInteractions(price, delay);
    }

    private FlightPredictionRequest selection() {
        return new FlightPredictionRequest("S7", "5104", "AER", "OVB", departure, "2026-11-14T13:00:00Z");
    }
}
