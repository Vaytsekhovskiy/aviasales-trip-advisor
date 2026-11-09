package avia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *   java -jar avia-seasonality.jar --mongo --airlines            ценовая политика авиакомпаний, все города из данных
 *   java -jar avia-seasonality.jar --mongo --airlines MOW LED    то же для выбранных городов (один HTML, вкладки)
 *       --min-pairs N, --min-destinations N, --all-snapshots как выше;
 *       --any-transfers    включить рейсы с пересадками (по умолчанию только прямые)
 */
public class MongoMain {

    private static final String CURRENCY = "rub";

    public static void main(String[] args) {
        boolean transfers = false;
        boolean airlines = false;
        boolean perSnapshot = true;
        boolean anyTransfers = false;
        Integer minPairsArg = null;
        int minDestinations = AirlineStats.DEFAULT_MIN_DESTINATIONS;
        List<String> codes = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i].toLowerCase(Locale.ROOT)) {
                    case "--transfers" -> transfers = true;
                    case "--airlines" -> airlines = true;
                    case "--all-snapshots" -> perSnapshot = false;
                    case "--any-transfers" -> anyTransfers = true;
                    case "--min-pairs" -> minPairsArg = Integer.parseInt(args[++i]);
                    case "--min-destinations" -> minDestinations = Integer.parseInt(args[++i]);
                    default -> codes.add(args[i].toUpperCase());
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            usageAndExit();
        }
        boolean validArgs = (transfers && airlines) ? false
                : transfers ? codes.size() == 1
                : airlines ? true
                : (codes.size() == 1 || codes.size() == 2);
        if (!validArgs) {
            usageAndExit();
        }

        try (MongoSource source = new MongoSource()) {
            if (transfers) {
                int minPairs = minPairsArg != null ? minPairsArg : PairedTransferStats.DEFAULT_MIN_PAIRS;
                runTransfers(source, codes.get(0), perSnapshot, minPairs);
            } else if (airlines) {
                int minPairs = minPairsArg != null ? minPairsArg : AirlineStats.DEFAULT_MIN_PAIRS;
                runAirlines(source, codes, perSnapshot, !anyTransfers, minPairs, minDestinations);
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

    private static void runAirlines(MongoSource source, List<String> requested, boolean perSnapshot,
                                    boolean directOnly, int minPairs, int minDestinations) throws IOException {
        boolean allOrigins = requested.isEmpty() || (requested.size() == 1 && requested.get(0).equals("ALL"));
        List<String> origins = allOrigins ? source.loadOrigins() : requested;
        if (origins.isEmpty()) {
            System.err.println("В offers_clean нет билетов prices_for_dates. Запустите: --clean");
            System.exit(2);
        }
        System.out.println("Ценовая политика авиакомпаний. Города вылета (" + origins.size() + "): "
                + String.join(", ", origins));
        System.out.println((directOnly ? "Только прямые рейсы" : "Все рейсы, включая с пересадками") + "; "
                + (perSnapshot ? "сравнение внутри одного среза" : "самая дешёвая цена на дату за все срезы"));

        List<AirlineChartsRenderer.Section> sections = new ArrayList<>();
        List<AirlineStats.Group> pooled = new ArrayList<>();
        List<String> csv = new ArrayList<>();
        csv.add("origin,airline_code,airline_name,destinations,comparisons,median_premium_pct,p25_pct,p75_pct,"
                + "median_overpay_rub,cheapest_share_pct,most_expensive_share_pct,far_premium_pct,near_premium_pct");

        for (String origin : origins) {
            List<AirlineStats.Group> groups = source.loadAirlineGroups(origin, directOnly, perSnapshot);
            if (groups.isEmpty()) {
                System.out.println(origin + ": нет дат, на которые маршрут предлагают минимум две авиакомпании, пропускаю");
                continue;
            }
            for (AirlineStats.Group g : groups) { // для общей вкладки направление = маршрут «ГОРОД-НАПРАВЛЕНИЕ»
                pooled.add(new AirlineStats.Group(origin + "-" + g.destination(), g.daysBefore(), g.prices()));
            }
            AirlineStats stats = new AirlineStats(groups, minPairs, minDestinations);
            System.out.println(origin + ": " + stats.diagnostics());
            if (stats.airlines().isEmpty()) {
                System.out.println("  данных для отчёта по этому городу недостаточно (уменьшите --min-pairs или --min-destinations)");
                continue;
            }
            sections.add(new AirlineChartsRenderer.Section(origin,
                    DestinationNames.resolve(origin, null) + " (" + origin + ")", stats,
                    source.describe("prices_for_dates", origin)));
            addAirlineCsv(csv, origin, stats);
        }
        if (sections.isEmpty()) {
            System.err.println("Ни по одному городу нет достаточно данных. Попробуйте --min-pairs 1 --min-destinations 2, "
                    + "--all-snapshots или --any-transfers.");
            System.exit(2);
        }
        if (sections.size() > 1) {
            AirlineStats pooledStats = new AirlineStats(pooled, minPairs, minDestinations);
            System.out.println("Все города вместе: " + pooledStats.diagnostics());
            sections.add(0, new AirlineChartsRenderer.Section("all", "Все города", pooledStats,
                    source.describe("prices_for_dates", null)));
            addAirlineCsv(csv, "ALL", pooledStats);
        }

        String tag = sections.size() == 1 ? sections.get(0).id() : "all";
        String csvFile = String.format("raw_airline_prices_%s_mongo.csv", tag);
        Files.write(Path.of(csvFile), csv, StandardCharsets.UTF_8);
        System.out.println("Агрегированные данные сохранены в " + csvFile);

        String file = String.format("airlines_comparison_%s_mongo.html", tag);
        AirlineChartsRenderer.render(Path.of(file), sections, directOnly);
        System.out.println("Отчёт сохранён в " + file + " (вкладок: " + sections.size() + ")");
    }

    private static void addAirlineCsv(List<String> lines, String origin, AirlineStats stats) {
        for (AirlineStats.Airline a : stats.airlines()) {
            lines.add(String.format(Locale.ROOT, "%s,%s,\"%s\",%d,%d,%.1f,%.1f,%.1f,%.0f,%.0f,%.0f,%s,%s",
                    origin, a.code(), a.name(), a.destinations(), a.comparisons(), a.medianPct(), a.p25Pct(),
                    a.p75Pct(), a.medianRub(), a.cheapestShare() * 100, a.mostExpensiveShare() * 100,
                    a.farPct() == null ? "" : String.format(Locale.ROOT, "%.1f", a.farPct()),
                    a.nearPct() == null ? "" : String.format(Locale.ROOT, "%.1f", a.nearPct())));
        }
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
        System.err.println("  --mongo --airlines [ORIGIN ...] [--min-pairs N] [--min-destinations N] [--all-snapshots] [--any-transfers]");
        System.exit(1);
    }
}