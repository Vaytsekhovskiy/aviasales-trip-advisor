package avia.client;

import avia.model.FlightBatch;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/** RapidAPI transport. Credentials are headers, never URL parameters or log output. */
public final class AeroDataBoxClient {
    public static final class HttpStatusException extends IOException {
        private final int status;
        public HttpStatusException(int status) {
            super("AeroDataBox HTTP " + status + "; check subscription, key, date range and quota");
            this.status = status;
        }
        public int status() { return status; }
    }
    private static final String HOST = "aerodatabox.p.rapidapi.com";
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private final HttpClient http;
    private final String key;
    private final URI base;

    public AeroDataBoxClient(String key) {
        this(key, URI.create("https://" + HOST), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** Injectable transport/base for offline HTTP integration tests. */
    public AeroDataBoxClient(String key, URI base, HttpClient http) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("AERODATABOX_API_KEY is not available in this process environment");
        }
        this.key = key.trim();
        this.base = base;
        this.http = http;
    }

    public String fetchFlights(FlightBatch batch) throws IOException, InterruptedException {
        String codeType = batch.airport().length() == 3 ? "iata" : "icao";
        return get("/flights/airports/" + codeType + "/" + batch.airport() + "/"
                + LOCAL.format(batch.fromLocal()) + "/" + LOCAL.format(batch.toLocal())
                + "?direction=Both&withLeg=true&withCancelled=true&withCodeshared=false"
                + "&withCargo=false&withPrivate=false&withLocation=false");
    }

    public String health(String icao) throws IOException, InterruptedException {
        if (!icao.matches("[A-Z]{4}")) throw new IllegalArgumentException("Health check requires uppercase ICAO code");
        return get("/health/services/airports/" + icao + "/feeds");
    }

    public String fetchAirport(String code) throws IOException, InterruptedException {
        if (!code.matches("[A-Z0-9]{3,4}")) {
            throw new IllegalArgumentException("Airport requires an uppercase IATA or ICAO code");
        }
        return get("/airports/" + (code.length() == 3 ? "iata" : "icao") + "/" + code);
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(60)).header("X-RapidAPI-Key", key)
                .header("X-RapidAPI-Host", HOST).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 204) {
            throw new IOException("AeroDataBox returned no content (204); batch not marked complete");
        }
        if (response.statusCode() != 200) {
            // Do not log raw remote bodies: proxies can echo request headers containing the key.
            throw new HttpStatusException(response.statusCode());
        }
        return response.body();
    }
}
