package avia;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Использование:
 *   java -jar avia-seasonality.jar <ORIGIN> <DESTINATION> <TOKEN> [CURRENCY]
 *   java -jar avia-seasonality.jar <ORIGIN> <TOKEN> [CURRENCY]
 *
 * Пример:
 *   java -jar avia-seasonality.jar MOW BKK 123abc456token rub
 *
 * Результат:
 *   heatmap_ORIGIN_DESTINATION.html   — тепловая карта "месяц x день недели"
 *   raw_prices_ORIGIN_DESTINATION.csv — сырые данные для дальнейшего анализа (pandas/Excel)
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            printUsageAndExit();
        }

        String origin = args[0].toUpperCase();
        boolean routeMode = args.length >= 4;

        String destination = null;
        String token;
        String currency;
        if (routeMode) {
            destination = args[1].toUpperCase();
            token = args[2];
            currency = args.length == 4 ? args[3] : "rub";
        } else {
            token = args[1];
            currency = args.length == 3 ? args[2] : "rub";
        }

        if (origin.isBlank() || token.isBlank() || (routeMode && destination.isBlank())) {
            printUsageAndExit();
        }

        try {
            AviasalesClient client = new AviasalesClient(token);
            List<PriceEntry> entries;
            String csvFileName;
            String heatmapFileName;

            if (routeMode) {
                System.out.println("Запрашиваю календарь цен " + origin + " → " + destination + " ...");
                entries = client.fetchYearCalendar(origin, destination, currency);
                csvFileName = String.format("raw_prices_%s_%s.csv", origin, destination);
                heatmapFileName = String.format("heatmap_%s_%s.html", origin, destination);
            } else {
                System.out.println("Запрашиваю календарь цен из " + origin + " по всем направлениям ...");
                entries = client.fetchYearCalendarAnyDestination(origin, currency);
                csvFileName = String.format("raw_prices_%s_any.csv", origin);
                heatmapFileName = String.format("heatmap_%s_any_destination.html", origin);
            }

            if (entries.isEmpty()) {
                System.err.println("API не вернул данных. Проверьте параметры, токен и лимиты запросов.");
                System.exit(2);
            }

            System.out.println("Получено дат с ценами: " + entries.size());
            writeCsv(Path.of(csvFileName), entries);
            System.out.println("Сырые данные сохранены в " + csvFileName);

            if (routeMode) {
                SeasonalityAggregator agg = new SeasonalityAggregator(entries);
                HeatmapHtmlRenderer.render(Path.of(heatmapFileName), origin, destination, currency, agg);
            } else {
                CheapestDestinationAggregator agg = new CheapestDestinationAggregator(entries);
                HeatmapHtmlRenderer.renderCheapestDestinations(Path.of(heatmapFileName), origin, currency, agg);
            }
            System.out.println("Тепловая карта сохранена в " + heatmapFileName + " — откройте её в браузере.");

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void writeCsv(Path path, List<PriceEntry> entries) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files_newBufferedWriter(path))) {
            pw.println("date,destination,price,transfers,month,weekday");
            for (PriceEntry e : entries) {
                String destination = e.destination() == null ? "" : e.destination();
                pw.printf("%s,%s,%d,%d,%d,%s%n",
                        e.date(), destination, e.price(), e.transfers(), e.monthIndex(), e.dayOfWeek());
            }
        }
    }

    private static void printUsageAndExit() {
        System.err.println("Использование:");
        System.err.println("  java -jar avia-seasonality.jar <ORIGIN> <DESTINATION> <TOKEN> [CURRENCY]");
        System.err.println("  java -jar avia-seasonality.jar <ORIGIN> <TOKEN> [CURRENCY]");
        System.err.println("Пример:");
        System.err.println("  java -jar avia-seasonality.jar MOW BKK YOUR_TOKEN rub");
        System.err.println("  java -jar avia-seasonality.jar MOW YOUR_TOKEN rub");
        System.exit(1);
    }

    // Небольшая обёртка, чтобы не тянуть лишний импорт в сигнатуру выше
    private static java.io.Writer Files_newBufferedWriter(Path path) throws IOException {
        return java.nio.file.Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8);
    }
}
