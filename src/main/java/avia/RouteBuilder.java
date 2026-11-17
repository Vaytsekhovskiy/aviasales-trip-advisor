package avia;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Собирает список популярных маршрутов (80–100 штук) и пишет его в файл для Collector.
 * Запускать ОДИН РАЗ, потом файл замораживается: набор маршрутов должен быть
 * одинаковым во всех срезах, иначе динамику цен (задача 5) не построить.
 *
 * Два источника:
 *  А) /v3/get_popular_directions?destination=X — популярные города ВЫЛЕТА для пункта назначения X
 *     (метод принимает именно destination и отдаёт origin);
 *  Б) /v3/prices_for_dates?origin=X&sorting=route&unique=true — популярные НАПРАВЛЕНИЯ из города X
 *     (документация рекомендует его вместо устаревшего /v1/city-directions).
 *
 * Запуск:
 *   java -cp target/avia-seasonality.jar avia.RouteBuilder ТОКЕН [routes_auto.txt]
 */
public class RouteBuilder {

    private static final String BASE = "https://api.travelpayouts.com/aviasales/v3";

    // Города назначения для источника А (внутренние и зарубежные)
    private static final String[] SEED_DESTINATIONS = {
            "MOW", "LED", "AER", "KZN", "SVX", "OVB", "KGD", "VVO",
            "IST", "BKK", "DXB", "AYT", "TBS"
    };
    private static final int PER_DESTINATION = 5; // у метода limit от 1 до 30

    // Города вылета для источника Б
    private static final String[] SEED_ORIGINS = {"MOW", "LED", "KZN", "SVX", "OVB", "KRR"};
    private static final int PER_ORIGIN = 7;

    private static final int MAX_ROUTES = 100;
    private static final long PAUSE_MS = 200;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Использование: RouteBuilder ТОКЕН [routes_auto.txt]");
            System.exit(1);
        }
        String token = args[0];
        String outFile = args.length > 1 ? args[1] : "routes_auto.txt";

        Set<String> routes = new LinkedHashSet<>(); // без дублей, порядок сохраняется

        for (String destination : SEED_DESTINATIONS) {
            for (String origin : popularOrigins(destination, token)) {
                addRoute(routes, origin, destination);
            }
            Thread.sleep(PAUSE_MS);
        }
        int fromA = routes.size();

        for (String origin : SEED_ORIGINS) {
            for (String destination : popularDestinations(origin, token)) {
                addRoute(routes, origin, destination);
            }
            Thread.sleep(PAUSE_MS);
        }
        System.out.println("Источник А (get_popular_directions): " + fromA
                + ", с источником Б всего уникальных: " + routes.size());
        if (fromA == 0) {
            System.err.println("get_popular_directions ничего не вернул — возможно, метод недоступен "
                    + "для вашего токена; маршруты взяты только из источника Б.");
        }

        List<String> lines = new ArrayList<>();
        lines.add("# Собрано автоматически " + LocalDate.now()
                + ". Не перегенерировать: набор маршрутов должен быть одинаковым во всех срезах.");
        List<String> list = new ArrayList<>(routes);
        lines.addAll(list.size() > MAX_ROUTES ? list.subList(0, MAX_ROUTES) : list);
        Files.write(Path.of(outFile), lines);
        System.out.println("Записано маршрутов: " + (lines.size() - 1) + " -> " + outFile);
    }

    /** Популярные города вылета для пункта назначения (get_popular_directions). */
    private static List<String> popularOrigins(String destination, String token) {
        List<String> result = new ArrayList<>();
        try {
            JSONObject root = Collector.get(BASE + "/get_popular_directions?destination=" + destination
                    + "&currency=rub&limit=" + PER_DESTINATION + "&page=1&token=" + token);
            JSONObject data = root.optJSONObject("data");
            JSONArray origins = data == null ? null : data.optJSONArray("origin");
            if (origins != null) {
                for (int i = 0; i < origins.length(); i++) {
                    JSONObject item = origins.optJSONObject(i);
                    if (item != null) {
                        result.add(item.optString("city_iata"));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("get_popular_directions " + destination + ": " + e.getMessage());
        }
        return result;
    }

    /** Популярные направления из города (prices_for_dates, сортировка по популярности маршрута). */
    private static List<String> popularDestinations(String origin, String token) {
        List<String> result = new ArrayList<>();
        try {
            JSONObject root = Collector.get(BASE + "/prices_for_dates?origin=" + origin
                    + "&sorting=route&unique=true&limit=" + PER_ORIGIN + "&currency=rub&token=" + token);
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item != null) {
                        result.add(item.optString("destination"));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("prices_for_dates (route) " + origin + ": " + e.getMessage());
        }
        return result;
    }

    private static void addRoute(Set<String> routes, String origin, String destination) {
        origin = origin.toUpperCase();
        destination = destination.toUpperCase();
        if (origin.matches("[A-Z]{3}") && destination.matches("[A-Z]{3}") && !origin.equals(destination)) {
            routes.add(origin + " " + destination);
        }
    }
}
