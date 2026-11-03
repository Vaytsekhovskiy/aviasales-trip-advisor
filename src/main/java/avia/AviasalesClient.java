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

    private final HttpClient http;
    private final String token;
    private final URI pricesBase;

    public static final class HttpStatusException extends IOException {
        private final int status;
        public HttpStatusException(int status) { super("Aviasales HTTP " + status); this.status = status; }
        public int status() { return status; }
    }

    /** Raw prices_for_dates response; caller owns retries and snapshot persistence. */
    public String fetchPricesForDates(String origin, String destination, java.time.YearMonth month)
            throws IOException, InterruptedException {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("AVIASALES_TOKEN is required");
        String url = pricesBase.resolve("/aviasales/v3/prices_for_dates").toString()
                + "?origin=" + enc(origin) + "&destination=" + enc(destination) + "&departure_at=" + month
                + "&one_way=true&direct=true&unique=false&sorting=price&currency=rub&market=ru&limit=1000&page=1"
                + "&token=" + enc(token);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(java.time.Duration.ofSeconds(45)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new HttpStatusException(response.statusCode());
        return decodeBody(response);
    }

    public AviasalesClient(String token) {
        this(token, URI.create("https://api.travelpayouts.com"),
                HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build());
    }

    /** Injectable transport/base for offline prices_for_dates integration checks. */
    public AviasalesClient(String token, URI pricesBase, HttpClient http) {
        this.token = token;
        this.pricesBase = pricesBase;
        this.http = http;
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
                    String destinationName = DestinationNames.resolve(destination, null);
                    result.add(new PriceEntry(date, price, transfers, destination, destinationName));
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
                String apiDestinationName = day.optString("destination_name", "");
                if (price > 0 && !destination.isBlank()) {
                    String destinationName = DestinationNames.resolve(destination, apiDestinationName);
                    result.add(new PriceEntry(date, price, transfers, destination, destinationName));
                }
            } catch (Exception e) {
                System.err.println("Пропущена запись " + dateKey + ": " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * Метод "Дешёвые билеты" (v1/prices/cheap) с destination="-": одним запросом
     * отдаёт по КАЖДОМУ направлению из origin до трёх предложений, сгруппированных
     * по числу пересадок (ключи "0"/"1"/"2" = прямой рейс/1 пересадка/2 пересадки).
     * Именно это позволяет сравнить прямые и пересадочные рейсы по разным
     * направлениям без накопления собственной истории цен.
     */
    public List<TransferOffer> fetchCheapestByTransfers(String origin, String currency)
            throws IOException, InterruptedException {

        String url = "https://api.travelpayouts.com/v1/prices/cheap"
                + "?origin=" + enc(origin)
                + "&destination=-"
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
        List<TransferOffer> result = new ArrayList<>();

        for (String destinationKey : data.keySet()) {
            String destination = destinationKey.toUpperCase();
            String destinationName = DestinationNames.resolve(destination, null);
            JSONObject byTransfers = data.getJSONObject(destinationKey);

            for (String transfersKey : byTransfers.keySet()) {
                try {
                    int transfers = Integer.parseInt(transfersKey);
                    if (transfers < 0 || transfers > 2) {
                        continue;
                    }
                    JSONObject offer = byTransfers.getJSONObject(transfersKey);
                    int price = offer.optInt("price", -1);
                    if (price > 0) {
                        result.add(new TransferOffer(destination, destinationName, transfers, price));
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Пропущен неожиданный ключ '" + transfersKey
                            + "' для направления " + destination);
                }
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
