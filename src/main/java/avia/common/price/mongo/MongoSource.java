package avia.common.price.mongo;

import avia.task4.analysis.AirlineStats;
import avia.common.aviasales.cli.Collector;
import avia.common.price.DestinationNames;
import avia.common.price.cli.Cleaner;
import avia.task1.model.PriceEntry;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.time.YearMonth;
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

    public MongoSource(MongoDatabase database) {
        this.client = null;
        this.clean = database.getCollection(Cleaner.CLEAN);
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

    public List<PriceEntry> loadSeasonality(String source, String origin, YearMonth month) {
        return loadPrices(priceFilter(source, origin, month));
    }

    /**
     * Данные для задачи 2.
     * minsByDestination: для каждой пары «дата вылета + срез» — самая дешёвая цена прямого,
     * 1 пересадки и 2+ пересадок (Integer.MAX_VALUE, если билетов этой категории нет).
     * ticketsByBucket: число уникальных билетов по категориям 0 / 1 / 2+ (характеристика данных).
     */
    public record TransferData(Map<String, List<int[]>> minsByDestination, int[] ticketsByBucket,
                               Map<String, Map<Integer, List<Integer>>> pricesByDestination) {
    }

    /**
     * perSnapshot = true: сравниваем билеты одного и того же среза (чище, но пар меньше).
     * perSnapshot = false: на каждую дату берём самую дешёвую цену категории за ВСЕ срезы
     * (пар больше, но цены сняты в разное время).
     */
    public TransferData loadTransferData(String origin, boolean perSnapshot) {
        return loadTransferData(origin, perSnapshot, null);
    }

    public TransferData loadTransferData(String origin, boolean perSnapshot, YearMonth month) {
        Map<String, int[]> mins = new HashMap<>(); // направление|дата[|срез] -> [мин0, мин1, мин2]
        Map<String, Map<Integer, List<Integer>>> prices = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int[] tickets = new int[3];
        Bson filter = priceFilter("prices_for_dates", origin, month);

        for (Document d : clean.find(filter).projection(Projections.include(
                "destination", "transfers", "price", "depart_date", "airline", "collected_at"))) {
            Integer transfers = d.getInteger("transfers");
            Integer price = d.getInteger("price");
            Date collectedAt = d.getDate("collected_at");
            if (transfers == null || transfers < 0 || price == null || price <= 0 || collectedAt == null) {
                continue;
            }
            String destination = d.getString("destination");
            String date = d.getString("depart_date");
            int bucket = Math.min(transfers, 2);

            // уникальные билеты (одно предложение из нескольких срезов считаем один раз)
            String offerKey = destination + "|" + date + "|" + d.getString("airline") + "|" + transfers + "|" + price;
            if (seen.add(offerKey)) {
                tickets[bucket]++;
                prices.computeIfAbsent(destination, key -> new HashMap<>())
                        .computeIfAbsent(bucket, key -> new ArrayList<>()).add(price);
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
        return new TransferData(byDestination, tickets, prices);
    }

    /** Города вылета, по которым есть билеты prices_for_dates (для режима «все города»). */
    public List<String> loadOrigins() {
        return loadOrigins("prices_for_dates");
    }

    public List<String> loadOrigins(String source) {
        List<String> origins = new ArrayList<>();
        clean.distinct("origin", Filters.eq("source", source), String.class).into(origins);
        origins.removeIf(origin -> origin == null || origin.isBlank());
        Collections.sort(origins);
        return origins;
    }

    public List<String> destinations(String source, String origin) {
        if (origin == null) return List.of();
        List<String> destinations = new ArrayList<>();
        clean.distinct("destination", priceFilter(source, origin, null), String.class).into(destinations);
        destinations.removeIf(destination -> destination == null || destination.isBlank());
        return destinations;
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
        return loadAirlineGroups(origin, directOnly, perSnapshot, null);
    }

    public List<AirlineStats.Group> loadAirlineGroups(String origin, boolean directOnly, boolean perSnapshot,
                                                     YearMonth month) {
        class Acc {
            String destination;
            int daysBefore;
            final Map<String, Integer> prices = new HashMap<>();
        }
        Map<String, Acc> groups = new HashMap<>(); // направление|дата[|срез] -> накопитель
        Bson filter = priceFilter("prices_for_dates", origin, month);
        if (directOnly) filter = Filters.and(filter, Filters.eq("transfers", 0));

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
    public record Snapshots(int count, LocalDate from, LocalDate to) { }

    public Snapshots snapshots(String source, String origin, YearMonth month) {
        List<Date> dates = new ArrayList<>();
        clean.distinct("collected_at", priceFilter(source, origin, month), Date.class).into(dates);
        dates.removeIf(java.util.Objects::isNull);
        if (dates.isEmpty()) {
            return new Snapshots(0, null, null);
        }
        Collections.sort(dates);
        return new Snapshots(dates.size(), utcDate(dates.get(0)), utcDate(dates.get(dates.size() - 1)));
    }

    public String describe(String source, String origin) {
        var period = snapshots(source, origin, null);
        return period.count() == 0 ? "данных нет (источник " + source + ")"
                : "срезов: " + period.count() + ", с " + period.from() + " по " + period.to();
    }

    private static LocalDate utcDate(Date date) {
        return date.toInstant().atOffset(java.time.ZoneOffset.UTC).toLocalDate();
    }

    private static Bson priceFilter(String source, String origin, YearMonth month) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("source", source));
        if (origin != null) filters.add(Filters.eq("origin", origin));
        if (month != null) {
            filters.add(Filters.gte("depart_date", month.atDay(1).toString()));
            filters.add(Filters.lt("depart_date", month.plusMonths(1).atDay(1).toString()));
        }
        return Filters.and(filters);
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
        if (client != null) client.close();
    }
}
