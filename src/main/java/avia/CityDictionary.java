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
 * Справочник городов Travelpayouts (тот же провайдер, что и Aviasales API):
 * http://api.travelpayouts.com/data/{lang}/cities.json
 *
 * Это открытые статические данные, которые Travelpayouts сами поддерживают
 * и обновляют — не нужно вести собственный хардкод-список городов.
 * Файл довольно большой (несколько тысяч городов), поэтому кэшируем его
 * локально и перекачиваем не чаще, чем раз в CACHE_TTL.
 */
public final class CityDictionary {

    private static final String URL_TEMPLATE = "https://api.travelpayouts.com/data/%s/cities.json";
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    private CityDictionary() {
    }

    /** Загружает справочник "IATA-код города -> название" для указанного языка (например, "ru"). */
    public static Map<String, String> load(String lang) throws IOException, InterruptedException {
        Path cacheFile = Path.of("cities_cache_" + lang + ".json");
        String json = readFromCacheIfFresh(cacheFile)
                .orElseGet(() -> null);

        if (json == null) {
            json = fetch(lang);
            try {
                Files.writeString(cacheFile, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // Кэш — это только оптимизация; если не получилось сохранить, просто продолжаем
                System.err.println("Не удалось сохранить кэш справочника городов: " + e.getMessage());
            }
        }

        return parse(json);
    }

    private static java.util.Optional<String> readFromCacheIfFresh(Path cacheFile) {
        try {
            if (!Files.exists(cacheFile)) return java.util.Optional.empty();
            Instant modified = Files.getLastModifiedTime(cacheFile).toInstant();
            if (Instant.now().isAfter(modified.plus(CACHE_TTL))) return java.util.Optional.empty();
            return java.util.Optional.of(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static String fetch(String lang) throws IOException, InterruptedException {
        String url = String.format(URL_TEMPLATE, lang);
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IOException("Справочник городов Travelpayouts вернул код " + response.statusCode());
        }
        return response.body();
    }

    private static Map<String, String> parse(String json) {
        Map<String, String> result = new HashMap<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject city = array.getJSONObject(i);
            String code = city.optString("code", null);
            String name = city.optString("name", null);
            if (code != null && name != null && !name.isBlank()) {
                result.put(code.toUpperCase(), name);
            }
        }
        return result;
    }
}
