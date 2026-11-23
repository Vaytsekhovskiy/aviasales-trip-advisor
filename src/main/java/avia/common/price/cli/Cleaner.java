package avia.common.price.cli;

import avia.common.aviasales.cli.Collector;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Очистка: читает сырые коллекции raw_* и строит единую коллекцию offers_clean.
 *
 * Что делает:
 *  - приводит разные названия полей разных эндпоинтов к одной схеме;
 *  - выбрасывает мусор (цена <= 0, нет даты вылета, вылет раньше даты среза, не эконом-класс);
 *  - убирает дубли внутри одного среза;
 *  - считает производные поля: месяц, день недели, days_before;
 *  - определяет trip_type (oneway / roundtrip / unknown).
 *
 * По умолчанию работает инкрементально: обрабатывает только новые срезы
 * (последний уже очищенный срез пересчитывается заново на случай обрыва).
 * Флаг --rebuild удаляет offers_clean и строит всё с нуля — нужен, если вы изменили правила очистки.
 *
 * Запуск:  java -jar avia-seasonality.jar --clean [--rebuild]
 */
public class Cleaner {

    public static final String CLEAN = "offers_clean";
    private static final int BATCH = 1000;
    private static final List<String> IDENTITY_FIELDS = List.of("origin", "destination", "depart_date", "airline",
            "gate", "price", "transfers", "trip_type", "found_at");

    /** Как называются поля в каждом эндпоинте. null — такого поля нет. */
    private record Source(String name, String collection,
                          String price, String depart, String transfers,
                          String airline, String returnAt) {
    }

    private static final List<Source> SOURCES = List.of(
            new Source("calendar", "raw_calendar", "price", "departure_at", "transfers", "airline", "return_at"),
            new Source("prices_for_dates", "raw_prices_for_dates", "price", "departure_at", "transfers", "airline", "return_at"),
            new Source("grouped", "raw_grouped", "price", "departure_at", "transfers", "airline", "return_at"),
            new Source("latest", "raw_latest", "value", "depart_date", "number_of_changes", null, "return_date"),
            new Source("matrix", "raw_matrix", "price", "depart_date", "transfers", "main_airline", null)
    );

    public static void main(String[] args) {
        boolean rebuild = args.length > 0 && args[0].equals("--rebuild");

        try (MongoClient client = MongoClients.create(Collector.MONGO_URI)) {
            MongoDatabase db = client.getDatabase(Collector.DB_NAME);
            MongoCollection<Document> clean = db.getCollection(CLEAN);

            if (rebuild) {
                clean.drop();
                System.out.println("offers_clean удалена, строю заново.");
            }
            clean.createIndex(Indexes.compoundIndex(Indexes.ascending("source"), Indexes.descending("collected_at")));
            clean.createIndex(Indexes.compoundIndex(
                    Indexes.ascending("source"), Indexes.ascending("origin"), Indexes.ascending("destination")));

            for (Source source : SOURCES) {
                cleanSource(db, clean, source);
            }
            System.out.println("Готово. Документов в offers_clean: " + clean.estimatedDocumentCount());
        }
    }

    /** Prepare selected raw observations without removing any existing snapshots. */
    public static int cleanObservations(MongoDatabase db, String sourceName, Iterable<Document> observations) {
        Source source = SOURCES.stream().filter(item -> sourceName.equals(item.name())).findFirst().orElseThrow();
        MongoCollection<Document> clean = db.getCollection(CLEAN);
        clean.createIndex(Indexes.ascending("source", "origin", "destination", "collected_at", "depart_date"));
        List<WriteModel<Document>> writes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int kept = 0;
        for (Document raw : observations) {
            Date collectedAt = raw.getDate("_collected_at");
            if (collectedAt == null) continue;
            Document normalized = convert(source, raw, collectedAt);
            if (normalized == null || !seen.add(collectedAt.getTime() + "|" + duplicateKey(normalized))) continue;
            Document filter = new Document("source", sourceName).append("collected_at", collectedAt);
            IDENTITY_FIELDS.forEach(field -> filter.put(field, normalized.get(field)));
            writes.add(new UpdateOneModel<>(filter, new Document("$setOnInsert", normalized), new UpdateOptions().upsert(true)));
            kept++;
            if (writes.size() == BATCH) {
                clean.bulkWrite(writes, new BulkWriteOptions().ordered(false));
                writes.clear();
            }
        }
        if (!writes.isEmpty()) clean.bulkWrite(writes, new BulkWriteOptions().ordered(false));
        return kept;
    }

    private static void cleanSource(MongoDatabase db, MongoCollection<Document> clean, Source source) {
        MongoCollection<Document> raw = db.getCollection(source.collection());
        if (raw.estimatedDocumentCount() == 0) {
            System.out.println(source.name() + ": сырых данных нет, пропускаю");
            return;
        }
        raw.createIndex(Indexes.ascending("_collected_at"));

        // последний уже очищенный срез этого источника — пересчитаем его заново вместе с новыми
        Document lastDoc = clean.find(Filters.eq("source", source.name()))
                .sort(Sorts.descending("collected_at")).limit(1).first();
        Date from = lastDoc == null ? null : lastDoc.getDate("collected_at");
        if (from != null) {
            clean.deleteMany(Filters.and(Filters.eq("source", source.name()), Filters.gte("collected_at", from)));
        }

        List<Date> snapshots = new ArrayList<>();
        raw.distinct("_collected_at", Date.class).into(snapshots);
        Collections.sort(snapshots);

        for (Date snapshot : snapshots) {
            if (from != null && snapshot.before(from)) {
                continue;
            }
            int read = 0;
            int kept = 0;
            Set<String> seen = new HashSet<>(); // дубли ищем только внутри одного среза
            List<Document> batch = new ArrayList<>();

            for (Document doc : raw.find(Filters.eq("_collected_at", snapshot))) {
                read++;
                Document cleanDoc = convert(source, doc, snapshot);
                if (cleanDoc == null || !seen.add(duplicateKey(cleanDoc))) {
                    continue;
                }
                batch.add(cleanDoc);
                kept++;
                if (batch.size() >= BATCH) {
                    clean.insertMany(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                clean.insertMany(batch);
            }
            System.out.printf("%s, срез %s: прочитано %d, оставлено %d%n", source.name(), snapshot.toInstant(), read, kept);
        }
    }

    /** Одна сырая запись -> запись единой схемы, либо null, если запись негодная. */
    private static Document convert(Source source, Document raw, Date collectedAt) {
        Integer price = toInt(raw.get(source.price()));
        if (price == null || price <= 0) {
            return null;
        }
        Integer tripClass = toInt(raw.get("trip_class"));
        if (tripClass != null && tripClass != 0) {
            return null; // только эконом
        }

        LocalDate depart = parseDate(raw.get(source.depart()));
        if (depart == null) {
            return null;
        }
        LocalDate collectedDay = collectedAt.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
        long daysBefore = ChronoUnit.DAYS.between(collectedDay, depart);
        if (daysBefore < 0) {
            return null;
        }

        // маршрут берём из того, что мы сами запрашивали (_route), а не из ответа:
        // в ответе бывают коды аэропортов, а нам нужны одинаковые коды городов
        String[] route = Objects.toString(raw.get("_route"), "").split("-");
        if (route.length < 2) {
            return null;
        }

        Document out = new Document();
        out.put("source", source.name());
        out.put("collected_at", collectedAt);
        put(out, "found_at", parseInstant(raw.get("found_at")));
        out.put("origin", route[0]);
        out.put("destination", route[1]);
        out.put("depart_date", depart.toString());
        out.put("depart_month", depart.getMonthValue());
        out.put("depart_dow", depart.getDayOfWeek().getValue()); // 1 = понедельник ... 7 = воскресенье
        out.put("days_before", (int) daysBefore);
        out.put("trip_type", tripType(source, raw));
        out.put("price", price);
        put(out, "transfers", toInt(raw.get(source.transfers())));
        put(out, "airline", source.airline() == null ? null : text(raw.get(source.airline())));
        put(out, "flight_number", text(raw.get("flight_number")));
        put(out, "gate", text(raw.get("gate")));
        Integer duration = toInt(raw.get("duration"));
        put(out, "duration_min", duration != null && duration > 0 ? duration : null);
        Integer distance = toInt(raw.get("distance"));
        put(out, "distance_km", distance != null && distance > 0 ? distance : null); // -1 = неизвестно
        return out;
    }

    private static String tripType(Source source, Document raw) {
        if (source.returnAt() == null) {
            return "unknown";
        }
        return text(raw.get(source.returnAt())) != null ? "roundtrip" : "oneway";
    }

    private static String duplicateKey(Document d) {
        return String.join("|", IDENTITY_FIELDS.stream().map(field -> String.valueOf(d.get(field))).toList());
    }

    private static void put(Document d, String key, Object value) {
        if (value != null) {
            d.put(key, value);
        }
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() || s.equals("null") ? null : s;
    }

    /** Локальная дата вылета: первые 10 символов строки вида 2026-12-04 или 2026-12-04T10:30:00+03:00. */
    private static LocalDate parseDate(Object value) {
        String s = text(value);
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static Date parseInstant(Object value) {
        String s = text(value);
        if (s == null) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(s).toInstant());
        } catch (Exception e) {
            return null;
        }
    }
}
