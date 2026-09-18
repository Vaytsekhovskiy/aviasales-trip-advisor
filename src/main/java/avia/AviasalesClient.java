package avia;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Клиент для метода "Авиабилеты из города на любое число месяца"
 * (v1/prices/calendar). При departure_date="" API отдаёт по одной
 * самой дешёвой цене на каждый день ближайшего года — этого достаточно,
 * чтобы построить сезонную тепловую карту без своего накопления истории.
 */
public class AviasalesClient {

    private static final String BASE_URL = "https://api.travelpayouts.com/v1/prices/calendar";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String token;

    public AviasalesClient(String token) {
        this.token = token;
    }

    public List<PriceEntry> fetchYearCalendar(String origin, String destination, String currency)
            throws IOException, InterruptedException {

        String url = BASE_URL
                + "?origin=" + enc(origin)
                + "&destination=" + enc(destination)
                + "&departure_date="                     // пусто = год вперёд
                + "&calendar_type=departure_date"
                + "&currency=" + enc(currency)
                + "&token=" + enc(token);

        // Читаем тело как байты, а не как String: java.net.http.HttpClient не
        // распаковывает gzip сам, поэтому если сервер (или прокси) всё же
        // пришлёт Content-Encoding: gzip, мы распакуем вручную ниже.
        // Раньше здесь стоял заголовок "Accept-Encoding: gzip, deflate" —
        // это как раз и приводило к ошибке "A JSONObject text must begin
        // with '{'": в body() попадали сырые gzip-байты вместо JSON-текста.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        String bodyText = decodeBody(response);

        if (response.statusCode() != 200) {
            throw new IOException("Aviasales API вернул код " + response.statusCode() + ": " + bodyText);
        }

        JSONObject root = new JSONObject(bodyText);
        if (!root.optBoolean("success", false)) {
            throw new IOException("Aviasales API success=false, тело ответа: " + bodyText);
        }

        JSONObject data = root.getJSONObject("data");
        List<PriceEntry> result = new ArrayList<>();

        for (String dateKey : data.keySet()) {
            JSONObject day = data.getJSONObject(dateKey);
            try {
                LocalDate date = LocalDate.parse(dateKey);
                int price = day.optInt("price", -1);
                int transfers = day.optInt("transfers", -1);
                if (price > 0) {
                    result.add(new PriceEntry(date, price, transfers, destination));
                }
            } catch (Exception e) {
                // Пропускаем некорректные записи, не роняем весь разбор
                System.err.println("Пропущена запись " + dateKey + ": " + e.getMessage());
            }
        }
        return result;
    }

    public List<PriceEntry> fetchYearCalendarAnyDestination(String origin, String currency)
            throws IOException, InterruptedException {

        String url = BASE_URL
                + "?origin=" + enc(origin)
                + "&departure_date="
                + "&calendar_type=departure_date"
                + "&currency=" + enc(currency)
                + "&token=" + enc(token);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String bodyText = decodeBody(response);

        if (response.statusCode() != 200) {
            throw new IOException("Aviasales API вернул код " + response.statusCode() + ": " + bodyText);
        }

        JSONObject root = new JSONObject(bodyText);
        if (!root.optBoolean("success", false)) {
            throw new IOException("Aviasales API success=false, тело ответа: " + bodyText);
        }

        JSONObject data = root.getJSONObject("data");
        List<PriceEntry> result = new ArrayList<>();

        for (String dateKey : data.keySet()) {
            JSONObject day = data.getJSONObject(dateKey);
            try {
                LocalDate date = LocalDate.parse(dateKey);
                int price = day.optInt("price", -1);
                int transfers = day.optInt("transfers", -1);
                String destination = day.optString("destination", "").toUpperCase();
                if (price > 0 && !destination.isBlank()) {
                    result.add(new PriceEntry(date, price, transfers, destination));
                }
            } catch (Exception e) {
                System.err.println("Пропущена запись " + dateKey + ": " + e.getMessage());
            }
        }
        return result;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Декодирует тело ответа в строку, распаковывая gzip, если сервер его прислал. */
    private static String decodeBody(HttpResponse<byte[]> response) throws IOException {
        byte[] raw = response.body();
        boolean isGzip = response.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase().contains("gzip"))
                .orElse(false);

        if (!isGzip) {
            return new String(raw, StandardCharsets.UTF_8);
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzip.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
