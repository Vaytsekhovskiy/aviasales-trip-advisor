package avia.storage;
import avia.model.PredictableFlightProfile;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.RenameCollectionOptions;
import org.bson.Document;
import java.util.*;

public final class MongoPredictableFlightProfileStorage {
    public static final String COLLECTION = "predictable_flight_profiles";
    public static Document toDocument(PredictableFlightProfile p) {
        var k = p.profile(); var s = k.service();
        return new Document("_id", p.profileKey()).append("airlineIata", s.airline()).append("flightNumber", s.number())
                .append("originIata", s.origin()).append("destinationIata", s.destination())
                .append("departureWeekday", k.weekday().name()).append("departureHourLocal", k.hour())
                .append("delayHistoryFlights", p.delayHistoryFlights()).append("priceHistoryFlights", p.priceHistoryFlights());
    }
    /** Publish only a fully loaded staging collection; an empty result also replaces the old catalog. */
    public void rebuild(MongoDatabase db, Collection<PredictableFlightProfile> profiles) {
        String staging = COLLECTION + "_staging_" + UUID.randomUUID().toString().replace("-", "");
        db.createCollection(staging);
        var collection = db.getCollection(staging);
        try {
            List<Document> batch = new ArrayList<>();
            for (var p : profiles) {
                batch.add(toDocument(p));
                if (batch.size() == 1000) { collection.insertMany(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) collection.insertMany(batch);
            collection.renameCollection(new MongoNamespace(db.getName(), COLLECTION), new RenameCollectionOptions().dropTarget(true));
        } finally { collection.drop(); }
    }
}
