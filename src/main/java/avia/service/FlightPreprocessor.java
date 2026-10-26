package avia.service;

import avia.dto.AeroDataBoxFlightDto;
import avia.dto.AeroDataBoxFlightDto.Airport;
import avia.dto.AeroDataBoxFlightDto.Time;
import avia.model.FlightBatch;
import avia.model.FlightRecord;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure normalization: no network or storage calls. */
public final class FlightPreprocessor {
    private final Map<String, Airport> airportReferences;

    public FlightPreprocessor() {
        this(Map.of());
    }

    /** References are supplied by the caller; normalization never loads data itself. */
    public FlightPreprocessor(Map<String, Airport> airportReferences) {
        this.airportReferences = Map.copyOf(airportReferences);
    }

    public List<FlightRecord> process(String json, FlightBatch batch, Instant collectedAt) {
        JSONObject root = new JSONObject(json);
        if (!root.has("departures") && !root.has("arrivals")) {
            throw new IllegalArgumentException("Expected FIDS object with departures or arrivals");
        }
        Airport source = Airport.from(root.optJSONObject("sourceAirport"));
        Airport reference = airportReferences.get(batch.airport());
        if (reference != null) source = merge(source, reference);
        source = withSourceCode(source, batch.airport());
        List<FlightRecord> records = new ArrayList<>();
        for (String direction : List.of("departures", "arrivals")) {
            if (root.isNull(direction)) continue;
            JSONArray flights = root.getJSONArray(direction);
            for (int i = 0; i < flights.length(); i++) {
                try {
                    records.add(normalize(AeroDataBoxFlightDto.from(flights.getJSONObject(i)),
                            source, batch, direction.equals("departures"), collectedAt));
                } catch (RuntimeException e) {
                    // Fail the batch instead of silently losing rows and marking it complete.
                    throw new IllegalArgumentException("Invalid " + direction + " record at index " + i, e);
                }
            }
        }
        return List.copyOf(records);
    }

    private FlightRecord normalize(AeroDataBoxFlightDto f, Airport source, FlightBatch batch,
                                   boolean departure, Instant collectedAt) {
        var d = f.departure();
        var a = f.arrival();
        Airport origin = departure ? merge(d.airport(), source) : d.airport();
        Airport destination = departure ? a.airport() : merge(a.airport(), source);
        Instant sd = utc(d.scheduled()), rd = utc(d.revised()), wd = utc(d.runway());
        Instant sa = utc(a.scheduled()), ra = utc(a.revised()), wa = utc(a.runway());
        LocalDateTime dl = local(d.scheduled()), al = local(a.scheduled());
        boolean cancelled = "Canceled".equalsIgnoreCase(f.status()) || "Cancelled".equalsIgnoreCase(f.status());
        Long dd = cancelled ? null : delta(sd, rd), ad = cancelled ? null : delta(sa, ra);
        String airline = upper(f.airlineIata());
        String number = flightNumber(f.number(), airline, upper(f.airlineIcao()));
        // An incomplete identity must not collapse unrelated flights into one synthetic ID.
        String id = airline == null || number == null || origin.iata() == null
                || destination.iata() == null || sd == null ? null
                : String.join("|", airline, number, upper(origin.iata()), upper(destination.iata()), sd.toString());
        return new FlightRecord(1, id, batch.airport(), departure ? "departure" : "arrival",
                number, airline, upper(f.airlineIcao()), f.airlineName(),
                upper(origin.iata()), upper(origin.icao()), origin.name(), origin.country(), origin.timezone(), origin.lat(), origin.lon(),
                upper(destination.iata()), upper(destination.icao()), destination.name(), destination.country(), destination.timezone(), destination.lat(), destination.lon(),
                f.status(), cancelled,
                sd, dl, rd, local(d.revised()), wd, local(d.runway()),
                sa, al, ra, local(a.revised()), wa, local(a.runway()),
                dd, ad, dd == null || ad == null ? null : dd - ad,
                cancelled ? null : delta(sd, wd), cancelled ? null : delta(sa, wa),
                dl == null ? null : dl.getDayOfWeek(), dl == null ? null : dl.getHour(),
                al == null ? null : al.getDayOfWeek(), al == null ? null : al.getHour(),
                f.aircraftModel(), f.aircraftReg(), d.terminal(), a.terminal(), d.gate(), a.gate(), collectedAt);
    }

    private static Airport withSourceCode(Airport source, String code) {
        String supplied = code.length() == 3 ? source.iata() : source.icao();
        if (supplied != null && !supplied.equalsIgnoreCase(code)) {
            throw new IllegalArgumentException("sourceAirport does not match batch airport");
        }
        return new Airport(code.length() == 3 ? code : source.iata(), code.length() == 4 ? code : source.icao(),
                source.name(), source.country(), source.timezone(), source.lat(), source.lon());
    }

    private static Airport merge(Airport airport, Airport source) {
        if (airport.iata() != null && source.iata() != null && !airport.iata().equalsIgnoreCase(source.iata())
                || airport.icao() != null && source.icao() != null && !airport.icao().equalsIgnoreCase(source.icao())) {
            throw new IllegalArgumentException("Observed airport conflicts with batch source");
        }
        return new Airport(first(airport.iata(), source.iata()), first(airport.icao(), source.icao()),
                first(airport.name(), source.name()), first(airport.country(), source.country()),
                first(airport.timezone(), source.timezone()), first(airport.lat(), source.lat()), first(airport.lon(), source.lon()));
    }

    private static <T> T first(T value, T fallback) { return value == null ? fallback : value; }
    private static String upper(String s) { return s == null ? null : s.toUpperCase(Locale.ROOT); }

    private static String flightNumber(String value, String iata, String icao) {
        if (value == null) return null;
        String number = upper(value).replaceAll("\\s+", "");
        for (String code : new String[]{icao, iata}) {
            if (code != null && number.startsWith(code) && number.length() > code.length()) {
                return number.substring(code.length());
            }
        }
        return number;
    }

    private static Instant utc(Time time) {
        if (time.utc() == null) return null;
        return OffsetDateTime.parse(time.utc().replace(' ', 'T')).toInstant();
    }

    private static LocalDateTime local(Time time) {
        if (time.local() == null) return null;
        String value = time.local().replace(' ', 'T');
        try { return OffsetDateTime.parse(value).toLocalDateTime(); }
        catch (java.time.format.DateTimeParseException e) { return LocalDateTime.parse(value); }
    }

    private static Long delta(Instant scheduled, Instant revised) {
        return scheduled == null || revised == null ? null : Duration.between(scheduled, revised).toMinutes();
    }
}
