package avia.storage;

import avia.model.FlightBatch;
import avia.model.FlightRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.List;

/** Stores each collected batch in data/flights/yyyy-MM-dd/AIRPORT_HH-HH.csv. */
public final class CsvFlightStorage implements FlightStorage, FlightReader {

    private static final String HEADER = String.join(",",
            "schema_version", "flight_id", "source_airport", "direction", "flight_number",
            "airline_iata", "airline_icao", "airline_name",
            "origin_iata", "origin_icao", "origin_name", "origin_country", "origin_timezone", "origin_lat", "origin_lon",
            "destination_iata", "destination_icao", "destination_name", "destination_country", "destination_timezone", "destination_lat", "destination_lon",
            "status", "is_cancelled",
            "scheduled_departure_utc", "scheduled_departure_local", "revised_departure_utc", "revised_departure_local", "runway_departure_utc", "runway_departure_local",
            "scheduled_arrival_utc", "scheduled_arrival_local", "revised_arrival_utc", "revised_arrival_local", "runway_arrival_utc", "runway_arrival_local",
            "departure_delay_min", "arrival_delay_min", "delay_recovery_min", "takeoff_delta_min", "landing_delta_min",
            "departure_weekday", "departure_hour", "arrival_weekday", "arrival_hour",
            "aircraft_model", "aircraft_reg", "departure_terminal", "arrival_terminal", "departure_gate", "arrival_gate", "collected_at_utc");

    private final Path root;

    public CsvFlightStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void save(List<FlightRecord> flights, FlightBatch batch) throws IOException {
        Path target = pathFor(batch);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), batch.airport() + "_", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                for (FlightRecord flight : flights) {
                    writer.write(toCsv(flight));
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public boolean exists(FlightBatch batch) {
        return Files.isRegularFile(pathFor(batch));
    }

    public Path pathFor(FlightBatch batch) {
        return root.resolve(batch.date().toString()).resolve(batch.fileName()).normalize();
    }

    @Override
    public List<FlightRecord> read(FlightBatch batch) throws IOException {
        try (var reader = Files.newBufferedReader(pathFor(batch), StandardCharsets.UTF_8)) {
            return FlightCsvReader.read(reader);
        } catch (IOException e) {
            throw new IOException("Cannot read " + pathFor(batch) + ": " + e.getMessage(), e);
        }
    }

    private static String toCsv(FlightRecord f) {
        return row(
                f.schemaVersion(), f.flightId(), f.sourceAirport(), f.direction(), f.flightNumber(),
                f.airlineIata(), f.airlineIcao(), f.airlineName(),
                f.originIata(), f.originIcao(), f.originName(), f.originCountry(), f.originTimezone(), f.originLat(), f.originLon(),
                f.destinationIata(), f.destinationIcao(), f.destinationName(), f.destinationCountry(), f.destinationTimezone(), f.destinationLat(), f.destinationLon(),
                f.status(), f.cancelled(),
                f.scheduledDepartureUtc(), f.scheduledDepartureLocal(), f.revisedDepartureUtc(), f.revisedDepartureLocal(), f.runwayDepartureUtc(), f.runwayDepartureLocal(),
                f.scheduledArrivalUtc(), f.scheduledArrivalLocal(), f.revisedArrivalUtc(), f.revisedArrivalLocal(), f.runwayArrivalUtc(), f.runwayArrivalLocal(),
                f.departureDelayMin(), f.arrivalDelayMin(), f.delayRecoveryMin(), f.takeoffDeltaMin(), f.landingDeltaMin(),
                f.departureWeekday(), f.departureHour(), f.arrivalWeekday(), f.arrivalHour(),
                f.aircraftModel(), f.aircraftReg(), f.departureTerminal(), f.arrivalTerminal(), f.departureGate(), f.arrivalGate(), f.collectedAtUtc());
    }

    private static String row(Object... values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(escape(values[i]));
        }
        return result.toString();
    }

    private static String escape(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
