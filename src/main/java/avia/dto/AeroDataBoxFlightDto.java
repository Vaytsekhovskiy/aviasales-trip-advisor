package avia.dto;

import org.json.JSONObject;

/** API-only representation; missing objects and fields remain nullable. */
public record AeroDataBoxFlightDto(String number, String status, String airlineIata,
        String airlineIcao, String airlineName, Movement departure, Movement arrival,
        String aircraftModel, String aircraftReg) {

    public record Airport(String iata, String icao, String name, String country,
                          String timezone, Double lat, Double lon) {
        /** Airport details endpoint uses fullName and country.code rather than FIDS names. */
        public static Airport fromDetails(JSONObject o) {
            JSONObject location = o.optJSONObject("location");
            return new Airport(text(o, "iata"), text(o, "icao"), text(o, "fullName"),
                    text(o.optJSONObject("country"), "code"), text(o, "timeZone"),
                    decimal(location, "lat"), decimal(location, "lon"));
        }

        public static Airport from(JSONObject o) {
            if (o == null) return new Airport(null, null, null, null, null, null, null);
            JSONObject location = o.optJSONObject("location");
            return new Airport(text(o, "iata"), text(o, "icao"), text(o, "name"),
                    text(o, "countryCode"), text(o, "timeZone"),
                    decimal(location, "lat"), decimal(location, "lon"));
        }
    }

    public record Time(String utc, String local) {
        static Time from(JSONObject o) {
            return new Time(text(o, "utc"), text(o, "local"));
        }
    }

    public record Movement(Airport airport, Time scheduled, Time revised, Time runway,
                           String terminal, String gate) {
        static Movement from(JSONObject o) {
            if (o == null) o = new JSONObject();
            return new Movement(Airport.from(o.optJSONObject("airport")),
                    Time.from(o.optJSONObject("scheduledTime")), Time.from(o.optJSONObject("revisedTime")),
                    Time.from(o.optJSONObject("runwayTime")), text(o, "terminal"), text(o, "gate"));
        }
    }

    public static AeroDataBoxFlightDto from(JSONObject o) {
        if (o.has("movement")) {
            throw new IllegalArgumentException("FIDS JSON must be requested with withLeg=true");
        }
        JSONObject airline = o.optJSONObject("airline");
        JSONObject aircraft = o.optJSONObject("aircraft");
        return new AeroDataBoxFlightDto(text(o, "number"), text(o, "status"),
                text(airline, "iata"), text(airline, "icao"), text(airline, "name"),
                Movement.from(o.optJSONObject("departure")), Movement.from(o.optJSONObject("arrival")),
                text(aircraft, "model"), text(aircraft, "reg"));
    }

    private static String text(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String value = o.getString(key).trim();
        return value.isEmpty() ? null : value;
    }

    private static Double decimal(JSONObject o, String key) {
        return o == null || o.isNull(key) ? null : o.getDouble(key);
    }
}
