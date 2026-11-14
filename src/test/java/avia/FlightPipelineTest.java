package avia;

import avia.model.FlightBatch;
import avia.service.FlightPreprocessor;
import avia.storage.CsvFlightStorage;
import avia.service.FlightCollector;
import avia.client.AeroDataBoxClient;
import avia.dto.AeroDataBoxFlightDto.Airport;
import org.json.JSONObject;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import java.time.*;

/** Offline regression checks; run this main class with test resources available from the project root. */
public final class FlightPipelineTest {
    public static void main(String[] args) throws Exception {
        var batch = new FlightBatch("svo", LocalDate.of(2026, 10, 3), LocalTime.MIDNIGHT, LocalTime.NOON);
        var records = new FlightPreprocessor().process(Files.readString(
                Path.of("src/test/resources/aerodatabox-synthetic.json")), batch, Instant.parse("2026-10-04T00:00:00Z"));
        check(records.size() == 3, "All records retained");
        var flight = records.get(0);
        check(flight.originIata().equals("SVO") && flight.destinationIata().equals("LED"), "Departure source substitution");
        check(flight.flightId().equals("SU|100|SVO|LED|2026-10-02T22:00:00Z"), "Stable ID");
        check(flight.departureDelayMin() == 40 && flight.arrivalDelayMin() == 15 && flight.delayRecoveryMin() == 25, "UTC delays and recovery");
        check(flight.departureWeekday() == DayOfWeek.SATURDAY && flight.departureHour() == 1, "Local weekday and hour");
        check(records.get(1).cancelled() && records.get(1).departureDelayMin() == null, "Cancelled flights retained without delay metrics");
        check(records.get(2).destinationIata().equals("SVO") && records.get(2).originIata().equals("OVB"), "Arrival source substitution");
        check(records.get(2).departureDelayMin() == null && records.get(2).flightId() == null, "Missing second leg remains null");
        check(records.get(2).arrivalDelayMin() == -5, "Early arrival preserved");
        Airport reference = Airport.fromDetails(new JSONObject("""
                {"iata":"SVO","icao":"UUEE","fullName":"Moscow Sheremetyevo",
                 "country":{"code":"RU","name":"Russia"},"timeZone":"Europe/Moscow",
                 "location":{"lat":55.97,"lon":37.41}}
                """));
        var enriched = new FlightPreprocessor(java.util.Map.of("SVO", reference)).process(Files.readString(
                Path.of("src/test/resources/aerodatabox-synthetic.json")), batch, flight.collectedAtUtc());
        check(enriched.get(0).originIcao().equals("UUEE") && enriched.get(0).originCountry().equals("RU")
                && enriched.get(0).originTimezone().equals("Europe/Moscow") && enriched.get(0).originLat() == 55.97,
                "Departure enriched from airport details contract");
        check(enriched.get(2).destinationName().equals("Moscow Sheremetyevo")
                && enriched.get(2).destinationIcao().equals("UUEE"), "Arrival enriched from source reference");
        check(enriched.get(0).flightId().equals(flight.flightId())
                && enriched.get(0).departureDelayMin().equals(flight.departureDelayMin()), "Reference preserves identity and delays");
        try {
            new FlightPreprocessor(java.util.Map.of("SVO", new Airport("LED", "ULLI", null, null, null, null, null)))
                    .process("{\"departures\":[]}", batch, flight.collectedAtUtc());
            throw new AssertionError("Mismatched reference accepted");
        } catch (IllegalArgumentException expected) { }
        var evening = new FlightBatch("SVO", batch.date(), LocalTime.NOON, LocalTime.MIDNIGHT);
        check(evening.fileName().equals("SVO_12-24.csv") && evening.toLocal().equals(batch.date().plusDays(1).atStartOfDay()), "Midnight rollover");
        try {
            new FlightBatch("SVO", batch.date(), LocalTime.of(0, 30), LocalTime.NOON);
            throw new AssertionError("Minute window accepted");
        } catch (IllegalArgumentException expected) { }
        Path root = Files.createTempDirectory("flight-pipeline-test-");
        var storage = new CsvFlightStorage(root);
        try {
            check(!storage.exists(batch), "New batch absent");
            storage.save(records, batch);
            check(storage.exists(batch), "Saved batch exists");
            String csv = Files.readString(storage.pathFor(batch));
            check(csv.contains("\"Test, \"\"Air\"\"\""), "CSV escaping");
            check(csv.lines().count() == 4, "CSV header and all rows");
            var collector = new FlightCollector(b -> { throw new AssertionError("Existing batch requested from API"); },
                    new FlightPreprocessor(), storage);
            check(collector.collect(batch) == -1, "Existing batch skips source entirely");
        } finally {
            Files.deleteIfExists(storage.pathFor(batch));
            Files.deleteIfExists(root.resolve(batch.date().toString()));
            Files.deleteIfExists(root);
        }
        verifyHttp(evening);
        System.out.println("FlightPipelineTest: passed");
    }

    private static void verifyHttp(FlightBatch batch) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> request = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> header = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            request.set(exchange.getRequestURI().toString());
            header.set(exchange.getRequestHeaders().getFirst("X-RapidAPI-Key"));
            boolean airportRequest = exchange.getRequestURI().getPath().equals("/airports/iata/SVO");
            byte[] body = (airportRequest ? "{\"iata\":\"SVO\"}"
                    : n == 1 ? "{\"departures\":[],\"arrivals\":[]}" : "do-not-log-secret").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n == 1 || airportRequest ? 200 : 429, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new AeroDataBoxClient("test-key", URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());
            check(client.fetchFlights(batch).contains("departures"), "HTTP response received");
            check(request.get().contains("/2026-10-03T12:00/2026-10-04T00:00"), "Request midnight rollover");
            check(request.get().contains("withLeg=true") && request.get().contains("direction=Both")
                    && request.get().contains("withCodeshared=false"), "FIDS query options");
            check("test-key".equals(header.get()) && !request.get().contains("test-key"), "Key only in header");
            try {
                client.fetchFlights(batch);
                throw new AssertionError("429 accepted");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("429") && !expected.getMessage().contains("do-not-log-secret"), "Safe HTTP error");
            }
            check(requests.get() == 2, "No automatic quota-consuming retry");
            check(client.fetchAirport("SVO").contains("SVO") && request.get().equals("/airports/iata/SVO"), "Airport details endpoint");
        } finally {
            server.stop(0);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
