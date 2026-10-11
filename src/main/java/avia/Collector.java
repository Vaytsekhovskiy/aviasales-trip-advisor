package avia;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сборщик сырых данных: для каждого маршрута из routes.txt вызывает 5 эндпоинтов
 * Aviasales и кладёт ответы в MongoDB КАК ЕСТЬ (по одной коллекции на эндпоинт,
 * один документ = одно предложение). К каждому документу добавляются служебные поля:
 *   _collected_at — время запуска сборщика (одинаково для всего среза),
 *   _endpoint     — имя коллекции/эндпоинта,
 *   _route        — маршрут, по которому делали запрос (например MOW-BKK).
 *
 * Запуск:
 *   java -cp target/avia-seasonality.jar avia.Collector ТОКЕН [routes.txt] [rub]
 *
 * Если MongoDB недоступна, документы пишутся в файл fallback_<коллекция>.jsonl
 * (потом можно загрузить: mongoimport --db avia --collection <имя> --file <файл>).
 */
public class Collector {

    private static final String MONGO_URI = System.getenv().getOrDefault(
            "MONGO_URI", "mongodb://localhost:27017/?serverSelectionTimeoutMS=3000");
    private static final String DB_NAME = "avia";
    private static final long PAUSE_MS = 300; // пауза между запросами, чтобы не упереться в лимиты

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Использование: Collector ТОКЕН [routes.txt] [currency]");
            System.exit(1);
        }
        String token = args[0];
        String routesFile = args.length > 1 ? args[1] : "routes.txt";
        String currency = args.length > 2 ? args[2] : "rub";

        Date collectedAt = new Date(); // один «момент среза» на весь запуск
        List<String[]> routes = readRoutes(routesFile);
        System.out.println("Маршрутов: " + routes.size() + ", срез: " + collectedAt);

        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = client.getDatabase(DB_NAME);
            for (String[] route : routes) {
                String routeName = route[0] + "-" + route[1];
                for (Map.Entry<String, String> ep : urls(route[0], route[1], currency).entrySet()) {
                    collectOne(db, ep.getKey(), routeName, ep.getValue(), token, collectedAt);
                    Thread.sleep(PAUSE_MS);
                }
            }
        }
        System.out.println("Готово.");
    }

    /** Имя коллекции -> URL эндпоинта (без токена). */
    private static Map<String, String> urls(String origin, String dest, String cur) {
        String base = "https://api.travelpayouts.com";
        String route = "origin=" + origin + "&destination=" + dest;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("raw_calendar", base + "/v1/prices/calendar?" + route
                + "&departure_date=&calendar_type=departure_date&currency=" + cur);
        m.put("raw_prices_for_dates", base + "/aviasales/v3/prices_for_dates?" + route
                + "&direct=false&sorting=price&limit=1000&currency=" + cur);
        m.put("raw_grouped", base + "/aviasales/v3/grouped_prices?" + route
                + "&currency=" + cur + "&group_by=departure_at");
        m.put("raw_latest", base + "/aviasales/v3/get_latest_prices?" + route
                + "&sorting=price&page=1&currency=" + cur);
        m.put("raw_matrix", base + "/v2/prices/nearest-places-matrix?" + route
                + "&distance=1000&limit=5");
        return m;
    }

    /** Один запрос -> список документов -> insertMany. Ошибка одного запроса не роняет весь сбор. */
    private static void collectOne(MongoDatabase db, String collection, String route,
                                   String url, String token, Date collectedAt) {
        try {
            JSONObject root = get(url + "&token=" + token);

            List<Document> docs = new ArrayList<>();
            for (JSONObject offer : extractOffers(root)) {
                Document d = Document.parse(offer.toString());
                d.put("_collected_at", collectedAt);
                d.put("_endpoint", collection);
                d.put("_route", route);
                docs.add(d);
            }
            if (docs.isEmpty()) {
                System.out.println(route + " " + collection + ": нет данных");
                return;
            }

            try {
                db.getCollection(collection).insertMany(docs);
            } catch (Exception mongoError) {
                saveToFile(collection, docs);
                System.err.println("MongoDB недоступна, записал в fallback_" + collection + ".jsonl");
            }
            System.out.println(route + " " + collection + ": " + docs.size());
        } catch (Exception e) {
            System.err.println(route + " " + collection + ": ошибка — " + e.getMessage());
        }
    }

    /**
     * Достаёт список предложений из ответа. Форматы у эндпоинтов разные:
     *  "data": {дата: {...}, ...}   — calendar, grouped_prices
     *  "data": [{...}, ...]         — prices_for_dates, get_latest_prices
     *  "prices": [{...}, ...]       — nearest-places-matrix
     */
    private static List<JSONObject> extractOffers(JSONObject root) {
        List<JSONObject> out = new ArrayList<>();
        Object data = root.opt("data");
        if (data == null) {
            data = root.opt("prices");
        }
        if (data instanceof JSONObject obj) {
            for (String key : obj.keySet()) {
                if (obj.get(key) instanceof JSONObject offer) {
                    out.add(offer);
                }
            }
        } else if (data instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                if (arr.get(i) instanceof JSONObject offer) {
                    out.add(offer);
                }
            }
        }
        return out;
    }

    static JSONObject get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) { // лимит запросов в минуту: ждём и пробуем ещё раз
            System.err.println("429: превышен лимит запросов, жду 60 с");
            Thread.sleep(60_000);
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) {
            String body = response.body();
            throw new IOException("HTTP " + response.statusCode() + ": "
                    + body.substring(0, Math.min(200, body.length())));
        }
        return new JSONObject(response.body());
    }

    /** Файл со строками вида "MOW BKK"; пустые строки и строки с # пропускаются. */
    private static List<String[]> readRoutes(String file) throws IOException {
        List<String[]> routes = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.toUpperCase().split("\\s+");
            if (parts.length >= 2) {
                routes.add(new String[]{parts[0], parts[1]});
            }
        }
        return routes;
    }

    private static void saveToFile(String collection, List<Document> docs) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Document d : docs) {
            lines.add(d.toJson());
        }
        Files.write(Path.of("fallback_" + collection + ".jsonl"), lines,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
