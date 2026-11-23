package avia.task5.delay.service;

import avia.common.config.AviaProperties;
import avia.task5.model.FlightPredictionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
public class DelayModelClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final URI endpoint;

    public DelayModelClient(ObjectMapper json, AviaProperties properties) {
        this.json = json;
        this.endpoint = properties.models().delayUrl().resolve("/predict");
    }

    public record Prediction(double predictedDelayMin, String modelVersion, JsonNode historicalContext) { }

    public Prediction predict(FlightPredictionRequest request) {
        try {
            var call = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build();
            var response = http.send(call, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 409) {
                throw new ResponseStatusException(CONFLICT, "Профиль рейса изменился. Обновите список доступных рейсов.");
            }
            if (response.statusCode() != 200) throw unavailable();
            JsonNode payload = json.readTree(response.body());
            if (payload == null) throw unavailable();
            JsonNode prediction = payload.path("prediction");
            double delay = prediction.path("predictedDelayMin").asDouble(Double.NaN);
            String version = prediction.path("modelVersion").asText();
            if (!Double.isFinite(delay) || version.isBlank()) throw unavailable();
            return new Prediction(delay, version, payload.path("historicalContext"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException error) {
            throw unavailable();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(SERVICE_UNAVAILABLE, "Модель задержки недоступна. Проверьте запуск сервиса CatBoost.");
    }
}
