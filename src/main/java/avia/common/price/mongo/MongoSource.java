package avia.common.price.mongo;

import avia.task4.analysis.AirlineStats;
import avia.common.aviasales.cli.Collector;
import avia.common.price.DestinationNames;
import avia.common.price.cli.Cleaner;
import avia.task1.model.PriceEntry;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Даёт существующим агрегаторам те же списки (PriceEntry, TransferOffer),
 * что раньше давал AviasalesClient, но берёт их из offers_clean в MongoDB.
 * Агрегаторы и HTML-рендереры при этом не меняются.
 *
 * Как накопленные срезы превращаются в одно значение:
 *  - сезонность: цена на каждую дату вылета усредняется по всем срезам
 *    (одна дата = одно значение, чтобы даты, попавшие в больше срезов, не весили больше других);
 *  - пересадки: для каждой даты вылета в каждом срезе берутся самые дешёвые прямой и
 *    пересадочный билеты, дальше PairedTransferStats сравнивает их между собой.
 */
public class MongoSource implements AutoCloseable {

    private final MongoClient client;
    private final MongoCollection<Document> clean;

    public MongoSource() {
        this.client = MongoClients.create(Collector.MONGO_URI);
        this.clean = client.getDatabase(Collector.DB_NAME).getCollection(Cleaner.CLEAN);
    }

    /** Аналог AviasalesClient.fetchYearCalendar: цены по датам вылета для одного маршрута. */
    public List<PriceEntry> loadCalendar(String origin, String destination) {
        return loadPrices(Filters.and(
                Filters.eq("source", "calendar"),
                Filters.eq("origin", origin),
                Filters.eq("destination", destination)));
    }

    /** Аналог AviasalesClient.fetchYearCalendarAnyDestination: все направления из города вылета. */
    public List<PriceEntry> loadCalendarAnyDestination(String origin) {
        return loadPrices(Filters.and(
                Filters.eq("source", "calendar"),
                Filters.eq("origin", origin)));
    }

    /**
     * Данные для задачи 2.
     * minsByDestination: для каждой пары «дата вылета + срез» — самая дешёвая цена прямого,
     * 1 пересадки и 2+ пересадок (Integer.MAX_VALUE, если билетов этой категории нет).
     * ticketsByBucket: число уникальных билетов по категориям 0 / 1 / 2+ (характеристика данных).
     */
    public record TransferData(Map<String, List<int[]>> minsByDestination, int[] ticketsByBucket) {
    }

    /**
     * perSnapshot = true: сравниваем билеты одного и того же среза (чище, но пар меньше).
     * perSnapshot = false: на каждую дату берём самую дешёвую цену категории за ВСЕ срезы
     * (пар больше, но цены сняты в разное время).
     */
    public TransferData loadTransferData(String origin, boolean perSnapshot) {
        Map<String, int[]> mins = new HashMap<>(); // направление|дата[|срез] -> [мин0, мин1, мин2]
        Set<String> seen = new HashSet<>();
        int[] tickets = new int[3];
        Bson filter = Filters.and(Filters.eq("source", "prices_for_dates"), Filters.eq("origin", origin));

        for (Document d : clean.find(filter).projection(Projections.include(
                "destination", "transfers", "price", "depart_date", "airline", "collected_at"))) {
            Integer transfers = d.getInteger("transfers");
            Integer price = d.getInteger("price");
            Date collectedAt = d.getDate("collected_at");
            if (transfers == null || transfers < 0 || price == null || collectedAt == null) {
                continue;
            }
            String destination = d.getString("destination");
            String date = d.getString("depart_date");
            int bucket = Math.min(transfers, 2);

            // уникальные билеты (одно предложение из нескольких срезов считаем один раз)
            String offerKey = destination + "|" + date + "|" + d.getString("airline") + "|" + transfers + "|" + price;
            if (seen.add(offerKey)) {
                tickets[bucket]++;
            }

            // самая дешёвая цена каждой категории на эту дату в этом срезе
            String pairKey = destination + "|" + date + (perSnapshot ? "|" + collectedAt.getTime() : "");
            int[] m = mins.computeIfAbsent(pairKey,
                    k -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE});
            m[bucket] = Math.min(m[bucket], price);
        }

        Map<String, List<int[]>> byDestination = new LinkedHashMap<>();
        for (var e : mins.entrySet()) {
            String destination = e.getKey().substring(0, e.getKey().indexOf('|'));
            byDestination.computeIfAbsent(destination, k -> new ArrayList<>()).add(e.getValue());
        }
        return new TransferData(byDestination, tickets);
    }

    /** Города вылета, по которым есть билеты prices_for_dates (для режима «все города»). */
    public List<String> loadOrigins() {
        List<String> origins = new ArrayList<>();
        clean.distinct("origin", Filters.eq("source", "prices_for_dates"), String.class).into(origins);
        Collections.sort(origins);
        return origins;
    }

    /**
     * Задача 4: цены авиакомпаний по группам «направление + дата вылета (+ срез)».
     * В группе — самая дешёвая цена каждой авиакомпании; группы, где только одна авиакомпания,
     * отбрасываются: сравнивать не с кем.
     *
     * directOnly = true: только прямые рейсы (сравнение чище: пересадки меняют цену).
     * perSnapshot = true: сравниваем цены одного среза (тогда известно, за сколько дней до вылета
     * они сняты); false — самая дешёвая цена на дату за все срезы (горизонт бронирования неизвестен).
     */
    public List<AirlineStats.Group> loadAirlineGroups(String origin, boolean directOnly, boolean perSnapshot) {
        class Acc {
            String destination;
            int daysBefore;
            final Map<String, Integer> prices = new HashMap<>();
        }
        Map<String, Acc> groups = new HashMap<>(); // направление|дата[|срез] -> накопитель
        Bson filter = directOnly
                ? Filters.and(Filters.eq("source", "prices_for_dates"), Filters.eq("origin", origin),
                Filters.eq("transfers", 0))
                : Filters.and(Filters.eq("source", "prices_for_dates"), Filters.eq("origin", origin));

        for (Document d : clean.find(filter).projection(Projections.include(
                "destination", "airline", "price", "depart_date", "collected_at", "days_before"))) {
            String airline = d.getString("airline");
            Integer price = d.getInteger("price");
            Date collectedAt = d.getDate("collected_at");
            if (airline == null || price == null || collectedAt == null) {
                continue;
            }
            String destination = d.getString("destination");
            Integer daysBefore = d.getInteger("days_before");
            String key = destination + "|" + d.getString("depart_date") + (perSnapshot ? "|" + collectedAt.getTime() : "");
            Acc g = groups.computeIfAbsent(key, k -> {
                Acc created = new Acc();
                created.destination = destination;
                created.daysBefore = perSnapshot && daysBefore != null ? daysBefore : -1;
                return created;
            });
            g.prices.merge(airline, price, Math::min);
        }

        List<AirlineStats.Group> result = new ArrayList<>();
        for (Acc g : groups.values()) {
            if (g.prices.size() >= 2) {
                result.add(new AirlineStats.Group(g.destination, g.daysBefore, g.prices));
            }
        }
        return result;
    }

    /** Сколько срезов и за какой период есть у источника (origin = null — по всем городам). */
    public String describe(String source, String origin) {
        List<Date> dates = new ArrayList<>();
        Bson filter = origin == null
                ? Filters.eq("source", source)
                : Filters.and(Filters.eq("source", source), Filters.eq("origin", origin));
        clean.distinct("collected_at", filter, Date.class).into(dates);
        if (dates.isEmpty()) {
            return "данных нет (источник " + source + ")";
        }
        Collections.sort(dates);
        return "срезов: " + dates.size() + ", с " + dates.get(0).toInstant().toString().substring(0, 10)
                + " по " + dates.get(dates.size() - 1).toInstant().toString().substring(0, 10);
    }

    private List<PriceEntry> loadPrices(Bson filter) {
        // одна дата вылета + направление = одно значение: среднее по всем срезам
        Bson group = Aggregates.group(
                new Document("destination", "$destination").append("date", "$depart_date"),
                Accumulators.avg("price", "$price"),
                Accumulators.min("transfers", "$transfers"));

        List<PriceEntry> result = new ArrayList<>();
        for (Document d : clean.aggregate(List.of(Aggregates.match(filter), group))) {
            Document id = d.get("_id", Document.class);
            String destination = id.getString("destination");
            Number transfers = (Number) d.get("transfers");
            result.add(new PriceEntry(
                    LocalDate.parse(id.getString("date")),
                    (int) Math.round(((Number) d.get("price")).doubleValue()),
                    transfers == null ? -1 : transfers.intValue(),
                    destination,
                    DestinationNames.resolve(destination, null)));
        }
        return result;
    }

    @Override
    public void close() {
        client.close();
    }
}
