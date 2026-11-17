
package avia;

import avia.common.aviasales.AviasalesClient;
import avia.common.aerodatabox.cli.DelayCli;
import avia.common.price.cli.Cleaner;
import avia.task3.cli.DatasetCli;
import avia.task5.model.FlightPredictionRequest;
import avia.task5.price.service.PricePredictionService;
import avia.task1.analysis.CheapestDestinationAggregator;
import avia.task1.analysis.SeasonalityAggregator;
import avia.task1.model.PriceEntry;
import avia.task1.report.HeatmapHtmlRenderer;
import avia.task2.analysis.TransferComparisonAggregator;
import avia.task2.model.TransferOffer;
import avia.task2.report.TransferComparisonHtmlRenderer;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Использование:
 *
 *   java -jar avia-seasonality.jar <ORIGIN> <DESTINATION> <TOKEN> [CURRENCY]
 *   java -jar avia-seasonality.jar <ORIGIN> <TOKEN> [CURRENCY]
 *   java -jar avia-seasonality.jar --transfers <ORIGIN> <TOKEN> [CURRENCY]
 *   java -jar avia-seasonality.jar --clean ...
 *   java -jar avia-seasonality.jar --mongo ...
 *
 * Прогноз цены:
 *
 *   java -jar avia-seasonality.jar --predict-price
 *       <AIRLINE> <FLIGHT_NUMBER> <ORIGIN> <DESTINATION>
 *       <DEPARTURE_WITH_OFFSET> <PREDICTION_TIME_UTC>
 *
 * Пример:
 *   java -jar avia-seasonality.jar MOW BKK 123abc456token rub
 *   java -jar avia-seasonality.jar --transfers MOW 123abc456token rub
 *
 *   java -jar avia-seasonality.jar --predict-price
 *       S7 5104 AER OVB 2026-11-14T13:20:00+03:00
 *       2026-11-03T10:00:00Z
 */
public class Main {

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--delays") || args[0].equals("--delays-import")
                || args[0].equals("--delays-health") || args[0].equals("--delays-airport")
                || args[0].equals("--delays-reprocess") || args[0].equals("--delays-plan")
                || args[0].equals("--delays-collect") || args[0].equals("--delays-report"))) {
            try {
                if (args[0].equals("--delays-plan") || args[0].equals("--delays-collect") || args[0].equals("--delays-report")) DatasetCli.run(args);
                else DelayCli.run(args);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Flight collection interrupted");
                System.exit(1);
            } catch (IOException | RuntimeException e) {
                System.err.println("Flight collection failed: " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("--transfers")) {
            runTransfersMode(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("--clean")) {
            Cleaner.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("--mongo")) {
            MongoMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("--predict-price")) {
            runPricePredictionMode(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        if (args.length < 2 || args.length > 4) {
            printUsageAndExit();
            return;
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

        if (origin.isBlank() || token.isBlank()
                || (routeMode && destination.isBlank())) {
            printUsageAndExit();
            return;
        }

        try {
            AviasalesClient client = new AviasalesClient(token);

            List<PriceEntry> entries;
            String csvFileName;
            String heatmapFileName;

            if (routeMode) {
                System.out.println(
                        "Запрашиваю календарь цен " + origin + " → "
                                + destination + " ..."
                );

                entries = client.fetchYearCalendar(
                        origin, destination, currency
                );

                csvFileName = String.format(
                        "raw_prices_%s_%s.csv", origin, destination
                );

                heatmapFileName = String.format(
                        "heatmap_%s_%s.html", origin, destination
                );

            } else {
                System.out.println(
                        "Запрашиваю календарь цен из " + origin
                                + " по всем направлениям ..."
                );

                entries = client.fetchYearCalendarAnyDestination(
                        origin, currency
                );

                csvFileName = String.format(
                        "raw_prices_%s_any.csv", origin
                );

                heatmapFileName = String.format(
                        "heatmap_%s_any_destination.html", origin
                );
            }

            if (entries.isEmpty()) {
                System.err.println(
                        "API не вернул данных. Проверьте параметры, "
                                + "токен и лимиты запросов."
                );
                System.exit(2);
                return;
            }

            System.out.println(
                    "Получено дат с ценами: " + entries.size()
            );

            writeCsv(Path.of(csvFileName), entries);

            System.out.println(
                    "Сырые данные сохранены в " + csvFileName
            );

            if (routeMode) {
                SeasonalityAggregator agg =
                        new SeasonalityAggregator(entries);

                HeatmapHtmlRenderer.render(
                        Path.of(heatmapFileName),
                        origin,
                        destination,
                        currency,
                        agg
                );

            } else {
                CheapestDestinationAggregator agg =
                        new CheapestDestinationAggregator(entries);

                HeatmapHtmlRenderer.renderCheapestDestinations(
                        Path.of(heatmapFileName),
                        origin,
                        currency,
                        agg
                );
            }

            System.out.println(
                    "Тепловая карта сохранена в " + heatmapFileName
                            + " — откройте её в браузере."
            );

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Прогнозирование цены конкретного рейса.
     *
     * Аргументы:
     * AIRLINE FLIGHT_NUMBER ORIGIN DESTINATION
     * DEPARTURE_WITH_OFFSET PREDICTION_TIME_UTC
     */
    private static void runPricePredictionMode(String[] args) {

        if (args.length != 6) {
            printPricePredictionUsageAndExit();
            return;
        }

        String airline = args[0].toUpperCase();
        String flightNumber = args[1];
        String origin = args[2].toUpperCase();
        String destination = args[3].toUpperCase();
        String departure = args[4];
        String predictionTime = args[5];

        if (airline.isBlank()
                || flightNumber.isBlank()
                || origin.isBlank()
                || destination.isBlank()
                || departure.isBlank()
                || predictionTime.isBlank()) {

            printPricePredictionUsageAndExit();
            return;
        }

        try {
            FlightPredictionRequest request =
                    new FlightPredictionRequest(
                            airline,
                            flightNumber,
                            origin,
                            destination,
                            departure,
                            predictionTime
                    );

            try (PricePredictionService service =
                         new PricePredictionService()) {

                System.out.println("Прогнозирование стоимости рейса...");
                System.out.println(
                        "Рейс: " + airline + " " + flightNumber
                );
                System.out.println(
                        "Маршрут: " + origin + " → " + destination
                );
                System.out.println("Вылет: " + departure);
                System.out.println(
                        "Время прогноза: " + predictionTime
                );

                float predictedPrice =
                        service.predictPrice(request);

                System.out.println("--------------------------------");
                System.out.println(
                        "Предсказанная стоимость: "
                                + String.format("%.2f", predictedPrice)
                );
                System.out.println("--------------------------------");
            }

        } catch (Exception e) {
            System.err.println(
                    "Ошибка прогнозирования цены: " + e.getMessage()
            );
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printPricePredictionUsageAndExit() {

        System.err.println("Использование:");
        System.err.println(
                "  java -jar avia-seasonality.jar --predict-price "
                        + "<AIRLINE> <FLIGHT_NUMBER> <ORIGIN> "
                        + "<DESTINATION> <DEPARTURE> <PREDICTION_TIME>"
        );

        System.err.println("Пример:");
        System.err.println(
                "  java -jar avia-seasonality.jar --predict-price "
                        + "S7 5104 AER OVB "
                        + "2026-11-14T13:20:00+03:00 "
                        + "2026-11-03T10:00:00Z"
        );

        System.exit(1);
    }

    private static void runTransfersMode(String[] args) {

        if (args.length < 2 || args.length > 3) {
            printTransfersUsageAndExit();
            return;
        }

        String origin = args[0].toUpperCase();
        String token = args[1];
        String currency = args.length == 3 ? args[2] : "rub";

        if (origin.isBlank() || token.isBlank()) {
            printTransfersUsageAndExit();
            return;
        }

        try {
            AviasalesClient client = new AviasalesClient(token);

            System.out.println(
                    "Запрашиваю дешёвые билеты (прямые / 1 пересадка "
                            + "/ 2 пересадки) из " + origin
                            + " по всем направлениям ..."
            );

            List<TransferOffer> offers =
                    client.fetchCheapestByTransfers(origin, currency);

            if (offers.isEmpty()) {
                System.err.println(
                        "API не вернул данных. Проверьте параметры, "
                                + "токен и лимиты запросов."
                );
                System.exit(2);
                return;
            }

            System.out.println(
                    "Получено предложений: " + offers.size()
            );

            String rawCsvFileName =
                    String.format("raw_transfers_%s.csv", origin);

            writeRawTransfersCsv(
                    Path.of(rawCsvFileName), offers
            );

            System.out.println(
                    "Сырые данные сохранены в " + rawCsvFileName
            );

            TransferComparisonAggregator agg =
                    new TransferComparisonAggregator(offers);

            String comparisonCsvFileName =
                    String.format("transfers_comparison_%s.csv", origin);

            writeComparisonCsv(
                    Path.of(comparisonCsvFileName),
                    agg.comparisons()
            );

            System.out.println(
                    "Сравнительная таблица сохранена в "
                            + comparisonCsvFileName
            );

            String htmlFileName =
                    String.format("transfers_comparison_%s.html", origin);

            TransferComparisonHtmlRenderer.render(
                    Path.of(htmlFileName),
                    origin,
                    currency,
                    agg.comparisons()
            );

            System.out.println(
                    "Диаграмма сравнения сохранена в " + htmlFileName
                            + " — откройте её в браузере."
            );

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void writeRawTransfersCsv(
            Path path,
            List<TransferOffer> offers
    ) throws IOException {

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8)
        )) {
            pw.println("destination,destination_name,transfers,price");

            for (TransferOffer o : offers) {
                pw.printf(
                        "%s,%s,%d,%d%n",
                        o.destination(),
                        o.destinationName(),
                        o.transfers(),
                        o.price()
                );
            }
        }
    }

    private static void writeComparisonCsv(
            Path path,
            List<TransferComparisonAggregator.Comparison> comparisons
    ) throws IOException {

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8)
        )) {
            pw.println(
                    "destination,destination_name,direct_price,"
                            + "one_stop_price,two_stop_price,"
                            + "cheapest_connecting_price,markup_abs,markup_pct"
            );

            for (var c : comparisons) {
                pw.printf(
                        "%s,%s,%s,%s,%s,%s,%s,%s%n",
                        c.destination(),
                        c.destinationName(),
                        str(c.directPrice()),
                        str(c.oneStopPrice()),
                        str(c.twoStopPrice()),
                        str(c.cheapestConnectingPrice()),
                        str(c.markupAbs()),
                        c.markupPct() == null
                                ? ""
                                : String.format("%.1f", c.markupPct())
                );
            }
        }
    }

    private static String str(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void printTransfersUsageAndExit() {
        System.err.println("Использование:");
        System.err.println(
                "  java -jar avia-seasonality.jar --transfers "
                        + "<ORIGIN> <TOKEN> [CURRENCY]"
        );
        System.err.println("Пример:");
        System.err.println(
                "  java -jar avia-seasonality.jar --transfers "
                        + "MOW YOUR_TOKEN rub"
        );
        System.exit(1);
    }

    private static void writeCsv(
            Path path,
            List<PriceEntry> entries
    ) throws IOException {

        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8)
        )) {
            pw.println(
                    "date,destination,price,transfers,month,weekday"
            );

            for (PriceEntry e : entries) {
                String destination =
                        e.destination() == null ? "" : e.destination();

                pw.printf(
                        "%s,%s,%d,%d,%d,%s%n",
                        e.date(),
                        destination,
                        e.price(),
                        e.transfers(),
                        e.monthIndex(),
                        e.dayOfWeek()
                );
            }
        }
    }

    private static void printUsageAndExit() {
        System.err.println("Использование:");
        System.err.println(
                "  java -jar avia-seasonality.jar "
                        + "<ORIGIN> <DESTINATION> <TOKEN> [CURRENCY]"
        );
        System.err.println(
                "  java -jar avia-seasonality.jar "
                        + "<ORIGIN> <TOKEN> [CURRENCY]"
        );
        System.err.println("Пример:");
        System.err.println(
                "  java -jar avia-seasonality.jar MOW BKK YOUR_TOKEN rub"
        );
        System.err.println(
                "  java -jar avia-seasonality.jar MOW YOUR_TOKEN rub"
        );
        System.exit(1);
    }
}
