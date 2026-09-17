package avia;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Использование:
 *   java -jar avia-seasonality.jar <ORIGIN> <DESTINATION> <TOKEN> [CURRENCY]
 *
 * Пример:
 *   java -jar avia-seasonality.jar MOW BKK 123abc456token rub
 *
 * Результат:
 *   heatmap.html   — тепловая карта "месяц x день недели"
 *   raw_prices.csv — сырые данные для дальнейшего анализа (pandas/Excel)
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Использование: java -jar avia-seasonality.jar <ORIGIN> <DESTINATION> <TOKEN> [CURRENCY]");
            System.err.println("Пример:        java -jar avia-seasonality.jar MOW BKK YOUR_TOKEN rub");
            System.exit(1);
        }

        String origin = args[0].toUpperCase();
        String destination = args[1].toUpperCase();
        String token = args[2];
        String currency = args.length >= 4 ? args[3] : "rub";

        try {
            System.out.println("Запрашиваю календарь цен " + origin + " → " + destination + " ...");
            AviasalesClient client = new AviasalesClient(token);
            List<PriceEntry> entries = client.fetchYearCalendar(origin, destination, currency);

            if (entries.isEmpty()) {
                System.err.println("API не вернул данных. Проверьте направление, токен и лимиты запросов.");
                System.exit(2);
            }
            System.out.println("Получено дат с ценами: " + entries.size());

            writeCsv(Path.of("raw_prices.csv"), entries);
            System.out.println("Сырые данные сохранены в raw_prices.csv");

            SeasonalityAggregator agg = new SeasonalityAggregator(entries);
            HeatmapHtmlRenderer.render(Path.of("heatmap.html"), origin, destination, currency, agg);
            System.out.println("Тепловая карта сохранена в heatmap.html — откройте её в браузере.");

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(3);
        }
    }

    private static void writeCsv(Path path, List<PriceEntry> entries) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files_newBufferedWriter(path))) {
            pw.println("date,price,transfers,month,weekday");
            for (PriceEntry e : entries) {
                pw.printf("%s,%d,%d,%d,%s%n",
                        e.date(), e.price(), e.transfers(), e.monthIndex(), e.dayOfWeek());
            }
        }
    }

    // Небольшая обёртка, чтобы не тянуть лишний импорт в сигнатуру выше
    private static java.io.Writer Files_newBufferedWriter(Path path) throws IOException {
        return java.nio.file.Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8);
    }
}
