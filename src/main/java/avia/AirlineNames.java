package avia;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Название авиакомпании по IATA-коду из открытого справочника Travelpayouts
 * (https://api.travelpayouts.com/data/ru/airlines.json). Работает так же, как
 * DestinationNames для городов: справочник кэшируется в файл на 30 дней,
 * а если загрузить его не удалось (например, нет сети), возвращается сам код.
 */
public final class AirlineNames {

    private static final String URL = "https://api.travelpayouts.com/data/ru/airlines.json";
    private static final Path CACHE_FILE = Path.of("airlines_cache_ru.json");
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    private static volatile Map<String, String> namesByCode;

    private AirlineNames() {
    }

    public static String resolve(String code) {
        String name = names().get(code.toUpperCase());
        return name != null ? name : code;
    }

    private static Map<String, String> names() {
        Map<String, String> local = namesByCode;
        if (local == null) {
            synchronized (AirlineNames.class) {
                local = namesByCode;
                if (local == null) {
                    local = safeLoad();
                    namesByCode = local;
                }
            }
        }
        return local;
    }

    private static Map<String, String> safeLoad() {
        try {
            return parse(readJson());
        } catch (Exception e) {
            System.err.println("Не удалось загрузить справочник авиакомпаний (" + e.getMessage()
                    + "), будут показаны IATA-коды.");
            return Map.of();
        }
    }

    private static String readJson() throws IOException, InterruptedException {
        try {
            if (Files.exists(CACHE_FILE)
                    && Files.getLastModifiedTime(CACHE_FILE).toInstant().isAfter(Instant.now().minus(CACHE_TTL))) {
                return Files.readString(CACHE_FILE, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // кэш — только оптимизация, при проблемах просто скачиваем заново
        }

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(URL)).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("справочник вернул код " + response.statusCode());
        }
        try {
            Files.writeString(CACHE_FILE, response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Не удалось сохранить кэш справочника авиакомпаний: " + e.getMessage());
        }
        return response.body();
    }

    private static Map<String, String> parse(String json) {
        Map<String, String> result = new HashMap<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject airline = array.getJSONObject(i);
            String code = airline.optString("code", null);
            String name = airline.optString("name", null);
            if (name == null || name.isBlank()) { // русского названия нет — берём английское
                JSONObject translations = airline.optJSONObject("name_translations");
                name = translations == null ? null : translations.optString("en", null);
            }
            if (code != null && name != null && !name.isBlank()) {
                result.put(code.toUpperCase(), name);
            }
        }
        return result;
    }
}
