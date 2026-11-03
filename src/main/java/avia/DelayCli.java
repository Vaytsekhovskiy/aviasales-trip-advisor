package avia;

import avia.client.AeroDataBoxClient;
import avia.model.FlightBatch;
import avia.service.FlightCollector;
import avia.service.FlightCollector.CollectedJson;
import avia.service.FlightPreprocessor;
import avia.storage.CsvFlightStorage;
import avia.dto.AeroDataBoxFlightDto.Airport;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.Locale;
import java.util.Map;

/** CLI adapter: chooses sources and storage while the collector remains independent. */
public final class DelayCli {
    private DelayCli() { }

    public static void run(String[] args) throws IOException, InterruptedException {
        if (args.length == 0) throw usage();
        if (args[0].equals("--delays-airport")) {
            if (args.length != 2) throw usage();
            String code = airportCode(args[1]);
            Path path = airportPath(code);
            if (Files.isRegularFile(path)) {
                airportReferences(code); // Validate the existing cache without spending units.
                System.out.println("Airport reference already exists: " + path);
            } else {
                String json = client().fetchAirport(code);
                validateAirport(code, json);
                writeJson(path, json);
                System.out.println("Saved airport reference: " + path);
            }
            return;
        }
        if (args[0].equals("--delays-health")) {
            if (args.length != 2) throw usage();
            System.out.println(client().health(args[1].toUpperCase(java.util.Locale.ROOT)));
            return;
        }
        boolean offline = args[0].equals("--delays-import");
        boolean reprocess = args[0].equals("--delays-reprocess");
        int offset = offline ? 2 : 1;
        if (offline ? args.length != 6 : args.length != 3 && args.length != 5) throw usage();
        String airport = airportCode(args[offset]);
        LocalDate date = LocalDate.parse(args[offset + 1]);
        var storage = new CsvFlightStorage(Path.of("data", "flights"));
        FlightCollector.Source source = offline
                ? batch -> read(Path.of(args[1]))
                : DelayCli::fetchCached;
        var preprocessor = new FlightPreprocessor(airportReferences(airport));
        var collector = new FlightCollector(source, preprocessor, storage);
        if (!offline && args.length == 3) {
            process(collector, storage, preprocessor, new FlightBatch(airport, date, LocalTime.MIDNIGHT, LocalTime.NOON), reprocess);
            process(collector, storage, preprocessor, new FlightBatch(airport, date, LocalTime.NOON, LocalTime.MIDNIGHT), reprocess);
        } else {
            process(collector, storage, preprocessor, new FlightBatch(airport, date,
                    parseHour(args[offset + 2], false), parseHour(args[offset + 3], true)), reprocess);
        }
    }

    private static void process(FlightCollector collector, CsvFlightStorage storage, FlightPreprocessor preprocessor,
                                FlightBatch batch, boolean reprocess) throws IOException, InterruptedException {
        if (!reprocess) {
            collect(collector, storage, batch);
            return;
        }
        CollectedJson raw = read(rawPath(batch)); // Local-only; failure never falls back to the API.
        var flights = preprocessor.process(raw.json(), batch, raw.collectedAt());
        storage.save(flights, batch);
        System.out.println("Reprocessed " + flights.size() + " flights from saved JSON: " + storage.pathFor(batch));
    }

    private static String airportCode(String value) {
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{3,4}")) throw new IllegalArgumentException("Expected IATA/ICAO airport code");
        return code;
    }

    private static Path airportPath(String code) {
        return Path.of("data", "reference", "airports", code + ".json");
    }

    private static Map<String, Airport> airportReferences(String code) throws IOException {
        Path path = airportPath(code);
        if (!Files.isRegularFile(path)) return Map.of();
        return Map.of(code, validateAirport(code, Files.readString(path, StandardCharsets.UTF_8)));
    }

    private static Airport validateAirport(String code, String json) {
        Airport airport = Airport.fromDetails(new JSONObject(json));
        String actual = code.length() == 3 ? airport.iata() : airport.icao();
        if (!code.equalsIgnoreCase(actual)) throw new IllegalArgumentException("Airport reference code mismatch");
        return airport;
    }

    private static Path rawPath(FlightBatch batch) {
        return Path.of("data", "raw", batch.date().toString(), batch.fileName().replace(".csv", ".json"));
    }

    private static void collect(FlightCollector collector, CsvFlightStorage storage, FlightBatch batch)
            throws IOException, InterruptedException {
        int count = collector.collect(batch);
        System.out.println(count == -1 ? "Batch already exists: " + storage.pathFor(batch)
                : "Saved " + count + " flights: " + storage.pathFor(batch));
    }

    private static LocalTime parseHour(String value, boolean end) {
        int hour = Integer.parseInt(value);
        return end && hour == 24 ? LocalTime.MIDNIGHT : LocalTime.of(hour, 0);
    }

    private static CollectedJson fetchCached(FlightBatch batch) throws IOException, InterruptedException {
        Path raw = rawPath(batch);
        if (Files.isRegularFile(raw)) return read(raw);
        String json = client().fetchFlights(batch);
        writeJson(raw, json);
        return read(raw);
    }

    private static void writeJson(Path raw, String json) throws IOException {
        Files.createDirectories(raw.getParent());
        Path temp = Files.createTempFile(raw.getParent(), "fids-", ".tmp");
        try {
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            try {
                Files.move(temp, raw, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, raw);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static CollectedJson read(Path file) throws IOException {
        return new CollectedJson(Files.readString(file, StandardCharsets.UTF_8), Files.getLastModifiedTime(file).toInstant());
    }

    private static AeroDataBoxClient client() {
        return new AeroDataBoxClient(System.getenv("AERODATABOX_API_KEY"));
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: --delays AIRPORT YYYY-MM-DD [FROM_HOUR TO_HOUR]\n"
                + "       --delays-import FILE.json AIRPORT YYYY-MM-DD FROM_HOUR TO_HOUR\n"
                + "       --delays-reprocess AIRPORT YYYY-MM-DD [FROM_HOUR TO_HOUR]\n"
                + "       --delays-airport AIRPORT\n"
                + "       --delays-health ICAO");
    }
}
