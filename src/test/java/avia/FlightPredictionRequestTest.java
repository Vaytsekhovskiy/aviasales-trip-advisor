package avia;

import avia.task5.model.FlightPredictionRequest;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlightPredictionRequestTest {
    private static FlightPredictionRequest request(String predictionTimeUtc) {
        return new FlightPredictionRequest("S7", "5104", "AER", "OVB",
                "2026-11-21T13:20:00+03:00", predictionTimeUtc);
    }

    @Test
    void acceptsUtcPredictionTimeBeforeDeparture() {
        assertDoesNotThrow(() -> request("2026-11-17T11:20:00Z"));
        assertDoesNotThrow(() -> request("2026-11-17T11:20:00+00:00"));
    }

    @Test
    void rejectsMissingNonUtcOrNonEarlierPredictionTime() {
        assertThrows(NullPointerException.class, () -> request(null));
        assertThrows(DateTimeParseException.class, () -> request("2026-11-17T11:20:00"));
        assertThrows(IllegalArgumentException.class, () -> request("2026-11-17T14:20:00+03:00"));
        assertThrows(IllegalArgumentException.class, () -> request("2026-11-21T10:20:00Z"));
        assertThrows(IllegalArgumentException.class, () -> request("2026-11-21T10:20:01Z"));
    }
}
