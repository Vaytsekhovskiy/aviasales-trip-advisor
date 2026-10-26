package avia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Те же три отчёта, что и в Main, но данные берутся из MongoDB (offers_clean), а не из API.
 * Агрегаторы и рендереры используются без изменений.
 *
 * Запуск (через флаг --mongo в Main):
 *   java -jar avia-seasonality.jar --mongo MOW BKK          сезонность по маршруту
 *   java -jar avia-seasonality.jar --mongo MOW              куда дешевле лететь из города
 *   java -jar avia-seasonality.jar --mongo --transfers MOW  прямые и рейсы с пересадками (три графика)
 *       --min-pairs N      минимум сравнений на направление (по умолчанию 3)
 *       --all-snapshots    сравнивать самую дешёвую цену на дату за все срезы, а не внутри одного
 */
public class MongoMain {

    private static final String CURRENCY = "rub";

    public static void main(String[] args) {
        boolean transfers = false;
        boolean perSnapshot = true;
        int minPairs = PairedTransferStats.DEFAULT_MIN_PAIRS;
        List<String> codes = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i].toLowerCase(Locale.ROOT)) {
                    case "--transfers" -> transfers = true;
                    case "--all-snapshots" -> perSnapshot = false;
                    case "--min-pairs" -> minPairs = Integer.parseInt(args[++i]);
                    default -> codes.add(args[i].toUpperCase());
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            usageAndExit();
        }
        boolean validArgs = transfers ? codes.size() == 1 : (codes.size() == 1 || codes.size() == 2);
        if (!validArgs) {
            usageAndExit();
        }

        try (MongoSource source = new MongoSource()) {
            if (transfers) {
                runTransfers(source, codes.get(0), perSnapshot, minPairs);
            } else if (codes.size() == 2) {
                runRoute(source, codes.get(0), codes.get(1));
            } else {
                runAnyDestination(source, codes.get(0));
            }
        } catch (IOException e) {
            System.err.println("Ошибка записи файла: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runRoute(MongoSource source, String origin, String destination) throws IOException {
        System.out.println("Сезонность " + origin + " → " + destination + ": " + source.describe("calendar", origin));
        List<PriceEntry> entries = source.loadCalendar(origin, destination);
        exitIfEmpty(entries.isEmpty(), "calendar");
        System.out.println("Дат вылета с ценами: " + entries.size());

        String file = String.format("heatmap_%s_%s_mongo.html", origin, destination);
        HeatmapHtmlRenderer.render(Path.of(file), origin, destination, CURRENCY, new SeasonalityAggregator(entries));
        System.out.println("Тепловая карта сохранена в " + file);
    }

    private static void runAnyDestination(MongoSource source, String origin) throws IOException {
        System.out.println("Куда дешевле лететь из " + origin + ": " + source.describe("calendar", origin));
        List<PriceEntry> entries = source.loadCalendarAnyDestination(origin);
        exitIfEmpty(entries.isEmpty(), "calendar");
        System.out.println("Записей (направление + дата вылета): " + entries.size());

        String file = String.format("heatmap_%s_any_destination_mongo.html", origin);
        HeatmapHtmlRenderer.renderCheapestDestinations(Path.of(file), origin, CURRENCY,
                new CheapestDestinationAggregator(entries));
        System.out.println("Тепловая карта сохранена в " + file);
    }

    private static void runTransfers(MongoSource source, String origin, boolean perSnapshot, int minPairs)
            throws IOException {
        String period = source.describe("prices_for_dates", origin);
        System.out.println("Прямые и пересадочные рейсы из " + origin + ": " + period);

        MongoSource.TransferData data = source.loadTransferData(origin, perSnapshot);
        if (data.minsByDestination().isEmpty()) {
            System.err.println("В offers_clean нет билетов prices_for_dates для " + origin
                    + ". Проверьте код города и запустите: --clean");
            System.exit(2);
        }
        int[] tickets = data.ticketsByBucket();
        System.out.println("Уникальных билетов: прямых " + tickets[0] + ", 1 пересадка " + tickets[1]
                + ", 2+ пересадки " + tickets[2]);

        PairedTransferStats stats = new PairedTransferStats(data.minsByDestination(), minPairs);
        System.out.println(perSnapshot ? "Режим: сравнение внутри одного среза"
                : "Режим: самая дешёвая цена на дату за все срезы");
        System.out.println(stats.diagnostics());
        printSummary(stats);

        String csv = String.format("raw_transfer_premiums_%s_mongo.csv", origin);
        writeCsv(Path.of(csv), origin, stats);
        System.out.println("Агрегированные данные сохранены в " + csv);

        String file = String.format("transfers_comparison_%s_mongo.html", origin);
        TransferChartsRenderer.render(Path.of(file), origin, CURRENCY, stats, tickets, period);
        System.out.println("Графики сохранены в " + file);
    }

    private static void printSummary(PairedTransferStats stats) {
        String[] names = {"", "1 пересадка", "2+ пересадки"};
        for (int i = 1; i <= 2; i++) {
            PairedTransferStats.Overall o = stats.overall(i);
            if (o == null) {
                System.out.println(names[i] + ": недостаточно данных для сравнения");
            } else {
                System.out.printf(Locale.ROOT,
                        "%s: медиана %+.0f%% к цене прямого (среднее %+.0f%%), направлений: %d, сравнений: %d, пересадочный дешевле в %d%% случаев%n",
                        names[i], o.medianPct(), o.meanPct(), o.directions(), o.pairs(), Math.round(o.shareCheaper() * 100));
            }
        }
    }

    /** По строке на направление и категорию — для приложения к пояснительной записке. */
    private static void writeCsv(Path path, String origin, PairedTransferStats stats) throws IOException {
        String[] names = {"", "1 пересадка", "2+ пересадки"};
        List<String> lines = new ArrayList<>();
        lines.add("origin,destination_code,destination_name,bucket,median_premium_pct,comparisons,share_cheaper_pct");
        for (PairedTransferStats.Destination d : stats.destinations()) {
            for (int i = 1; i <= 2; i++) {
                PairedTransferStats.Group g = d.group(i);
                if (g != null) {
                    lines.add(String.format(Locale.ROOT, "%s,%s,\"%s\",%s,%.1f,%d,%.0f",
                            origin, d.code(), d.name(), names[i], g.medianPct(), g.pairs(), g.shareCheaper() * 100));
                }
            }
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void exitIfEmpty(boolean empty, String sourceName) {
        if (empty) {
            System.err.println("В offers_clean нет подходящих данных (источник " + sourceName
                    + "). Проверьте код города и запустите: --clean");
            System.exit(2);
        }
    }

    private static void usageAndExit() {
        System.err.println("Использование:");
        System.err.println("  --mongo <ORIGIN> <DESTINATION>");
        System.err.println("  --mongo <ORIGIN>");
        System.err.println("  --mongo --transfers <ORIGIN> [--min-pairs N] [--all-snapshots]");
        System.exit(1);
    }
}