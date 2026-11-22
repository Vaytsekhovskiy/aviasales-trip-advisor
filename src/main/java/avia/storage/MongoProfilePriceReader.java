package avia.storage;
import avia.service.FlightProfileAggregator;
import com.mongodb.client.MongoDatabase;
import java.util.*;
public final class MongoProfilePriceReader {
    public List<FlightProfileAggregator.PriceFlight> readAll(MongoDatabase database) {
        List<FlightProfileAggregator.PriceFlight> flights = new ArrayList<>();
        try (var cursor = database.getCollection("raw_prices_for_dates").find().iterator()) {
            while (cursor.hasNext()) FlightProfileAggregator.priceFlight(cursor.next()).ifPresent(flights::add);
        }
        return flights;
    }
}
