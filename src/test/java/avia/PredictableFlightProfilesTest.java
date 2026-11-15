package avia;
import avia.common.aerodatabox.*;
import avia.common.aerodatabox.cli.*;
import avia.common.aerodatabox.mongo.*;
import avia.common.aviasales.*;
import avia.common.aviasales.cli.*;
import avia.common.flight.*;
import avia.common.flight.model.*;
import avia.common.flight.mongo.*;
import avia.common.flight.storage.*;
import avia.common.mongo.*;
import avia.task1.analysis.*;
import avia.task1.model.*;
import avia.task1.report.*;
import avia.task2.analysis.*;
import avia.task2.model.*;
import avia.task2.report.*;
import avia.task3.analysis.*;
import avia.task3.cli.*;
import avia.task3.model.*;
import avia.task3.mongo.*;
import avia.task3.report.*;
import avia.task3.service.*;
import avia.task4.analysis.*;
import avia.task4.report.*;
import avia.task5.delay.model.*;
import avia.task5.delay.mongo.*;
import avia.task5.delay.service.*;
import avia.task5.price.model.*;
import avia.task5.price.service.*;import org.bson.Document;
import java.util.*;
import java.time.*;
import java.lang.reflect.Proxy;
import com.mongodb.client.*;

public final class PredictableFlightProfilesTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static Document price(String time) { return new Document("airline", " dp ").append("flight_number", "DP 6822")
        .append("origin_airport", "led").append("destination_airport", "svo").append("departure_at", time); }
    public static void main(String[] args) {
        var row = price("2026-10-13T21:50:00+03:00");
        var flight = FlightProfileAggregator.priceFlight(row).orElseThrow();
        var key = flight.profile();
        check(key.toString().equals("DP|6822|LED|SVO|TUESDAY|21"));
        check(key.equals(new FlightProfileKey(new FlightServiceIdentity("DP", "6822", "LED", "SVO"), DayOfWeek.TUESDAY, 21)));
        check(!key.equals(new FlightProfileKey(key.service(), DayOfWeek.MONDAY,21)));
        check(!key.equals(new FlightProfileKey(key.service(), DayOfWeek.TUESDAY,20)));
        check(FlightProfileAggregator.priceFlight(price("2026-10-13T00:30:00+03:00")).orElseThrow().profile().weekday() == DayOfWeek.TUESDAY);
        for (String field : List.of("airline", "flight_number", "origin_airport", "destination_airport", "departure_at")) {
            var bad = new Document(row); bad.remove(field); check(FlightProfileAggregator.priceFlight(bad).isEmpty());
        }
        check(FlightProfileAggregator.priceFlight(price("invalid")).isEmpty());
        var aggregator = new FlightProfileAggregator();
        var repeat = FlightProfileAggregator.priceFlight(new Document(row).append("price", 42).append("_collected_at", new Date())).orElseThrow();
        var next = FlightProfileAggregator.priceFlight(price("2026-10-20T21:50:00+03:00")).orElseThrow();
        check(aggregator.priceCounts(List.of(flight, repeat, next)).get(key) == 2);
        var doc = new Document("schemaVersion",1).append("flightId","one").append("cancelled",false)
            .append("airlineIata","DP").append("flightNumber","6822").append("originIata","LED").append("destinationIata","SVO")
            .append("departureWeekday","TUESDAY").append("departureHour",21).append("departureDelayMin",8L);
        var f = MongoFlightReader.fromDocument(doc);
        check(aggregator.delayCounts(List.of(f,f)).get(key) == 1);
        var cancelled = MongoFlightReader.fromDocument(new Document(doc).append("cancelled",true).append("collectedAtUtc",new Date()));
        check(aggregator.delayCounts(List.of(f,cancelled)).isEmpty());
        var service = new PredictableFlightProfileService();
        check(service.intersect(Map.of(key,8L), Map.of()).isEmpty());
        check(service.intersect(Map.of(), Map.of(key,4L)).isEmpty());
        var profile = service.intersect(Map.of(key,8L), Map.of(key,4L)).get(0);
        check(profile.passes(5,3) && !profile.passes(5,5));
        storage(profile);
        System.out.println("PredictableFlightProfilesTest: passed (offline Mongo driver doubles)");
    }
    // Exercise the actual rebuild against a minimal driver double, including empty replacement.
    @SuppressWarnings("unchecked")
    static void storage(PredictableFlightProfile profile) {
        Map<String, List<Document>> collections = new HashMap<>();
        MongoDatabase db = (MongoDatabase) Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(), new Class[]{MongoDatabase.class}, (o,m,a) -> {
            switch (m.getName()) {
                case "getName": return "test";
                case "createCollection": collections.put((String)a[0], new ArrayList<>()); return null;
                case "getCollection":
                    String name = (String)a[0];
                    return Proxy.newProxyInstance(MongoCollection.class.getClassLoader(), new Class[]{MongoCollection.class}, (c,method,params) -> {
                        switch (method.getName()) {
                            case "insertMany": collections.get(name).addAll((List<Document>) params[0]); return null;
                            case "renameCollection": collections.put(((com.mongodb.MongoNamespace)params[0]).getCollectionName(),collections.remove(name)); return null;
                            case "drop": collections.remove(name); return null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
        var storage = new MongoPredictableFlightProfileStorage();
        storage.rebuild(db,List.of(profile)); storage.rebuild(db,List.of(profile));
        check(collections.get(MongoPredictableFlightProfileStorage.COLLECTION).size()==1);
        storage.rebuild(db,List.of()); check(collections.get(MongoPredictableFlightProfileStorage.COLLECTION).isEmpty());
    }
}
