package avia.service;

import avia.model.AviasalesFlightOffer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.json.*;

/** Offline prices_for_dates parser. Invalid individual offers are skipped;
 * malformed/unsuccessful response envelopes fail explicitly.
 */
public final class AviasalesFlightPreprocessor {
    public List<AviasalesFlightOffer> process(String json) {
        JSONObject response = new JSONObject(json);
        if (!response.getBoolean("success")) throw new IllegalArgumentException("Unsuccessful Aviasales response");
        String currency = response.getString("currency");
        JSONArray data = response.getJSONArray("data");
        List<AviasalesFlightOffer> offers = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            try {
                JSONObject row = data.getJSONObject(i);
                offers.add(new AviasalesFlightOffer(row.getString("airline"), row.getString("flight_number"),
                        row.getString("origin_airport"), row.getString("destination_airport"),
                        OffsetDateTime.parse(row.getString("departure_at")),
                        row.getBigDecimal("price").longValueExact(), currency,
                        row.getBigDecimal("transfers").intValueExact(), row.getBigDecimal("duration").intValueExact()));
            } catch (JSONException | IllegalArgumentException | DateTimeParseException | ArithmeticException invalidOffer) {
                // A partial/malformed row must never acquire a fabricated join key or zero price.
            }
        }
        return List.copyOf(offers);
    }
}
