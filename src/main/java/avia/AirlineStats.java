package avia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Задача 4: сравнение ценовой политики авиакомпаний на одинаковых направлениях.
 *
 * Одна «группа» = одно направление + одна дата вылета (+ один срез). В группе берётся самая
 * дешёвая цена каждой авиакомпании, и группа учитывается, только если в ней не меньше двух
 * авиакомпаний. Дальше для каждой авиакомпании считается:
 *  - надбавка к самой дешёвой цене в группе, % (0% — авиакомпания была дешевле всех) и в рублях;
 *  - доля групп, где она самая дешёвая и где самая дорогая;
 *  - надбавка отдельно для билетов «заранее» (за 30 дней и больше) и «близко к вылету» (меньше 30 дней).
 * Показатель по направлению — медиана по датам, показатель авиакомпании — медиана по направлениям,
 * так что популярные направления не перевешивают остальные.
 */
public class AirlineStats {

    /** Минимум сравнений на пару «авиакомпания + направление». */
    public static final int DEFAULT_MIN_PAIRS = 3;
    /** Минимум направлений, на которых авиакомпания участвует в сравнениях. */
    public static final int DEFAULT_MIN_DESTINATIONS = 3;
    /** Граница «заранее» / «близко к вылету», дней. */
    public static final int NEAR_DAYS = 30;

    /** daysBefore = -1, если неизвестно (режим «все срезы»). */
    public record Group(String destination, int daysBefore, Map<String, Integer> prices) {
    }

    public record Airline(String code, String name, int destinations, int comparisons,
                          double medianPct, double p25Pct, double p75Pct, double medianRub,
                          double cheapestShare, double mostExpensiveShare,
                          Double farPct, Double nearPct) {
    }

    public record Cell(double medianPct, int comparisons) {
    }

    private static class Acc {
        final List<Double> pct = new ArrayList<>();
        final List<Double> rub = new ArrayList<>();
        final List<Double> farPct = new ArrayList<>();
        final List<Double> nearPct = new ArrayList<>();
        int cheapest;
        int mostExpensive;
    }

    private final List<Airline> airlines = new ArrayList<>();
    private final Map<String, Map<String, Cell>> cells = new LinkedHashMap<>(); // направление -> авиакомпания -> ячейка
    private final int minPairs;
    private final int minDestinations;
    private int totalGroups;
    private int totalAirlines;

    public AirlineStats(List<Group> groups, int minPairs, int minDestinations) {
        this.minPairs = minPairs;
        this.minDestinations = minDestinations;

        Map<String, Map<String, Acc>> acc = new HashMap<>(); // авиакомпания -> направление -> накопитель
        for (Group group : groups) {
            totalGroups++;
            int min = Collections.min(group.prices().values());
            int max = Collections.max(group.prices().values());
            if (min <= 0) {
                continue;
            }
            for (var e : group.prices().entrySet()) {
                int price = e.getValue();
                Acc a = acc.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                        .computeIfAbsent(group.destination(), k -> new Acc());
                double pct = (price - min) * 100.0 / min;
                a.pct.add(pct);
                a.rub.add((double) (price - min));
                if (price == min) {
                    a.cheapest++;
                }
                if (max > min && price == max) {
                    a.mostExpensive++;
                }
                if (group.daysBefore() >= 0) {
                    (group.daysBefore() < NEAR_DAYS ? a.nearPct : a.farPct).add(pct);
                }
            }
        }

        totalAirlines = acc.size();
        for (var airlineEntry : acc.entrySet()) {
            List<Double> pctMedians = new ArrayList<>();
            List<Double> rubMedians = new ArrayList<>();
            List<Double> farMedians = new ArrayList<>();
            List<Double> nearMedians = new ArrayList<>();
            List<Double> pooled = new ArrayList<>();
            int comparisons = 0;
            int cheapest = 0;
            int mostExpensive = 0;
            for (Acc a : airlineEntry.getValue().values()) {
                if (a.pct.size() < minPairs) {
                    continue;
                }
                pctMedians.add(median(a.pct));
                rubMedians.add(median(a.rub));
                pooled.addAll(a.pct);
                comparisons += a.pct.size();
                cheapest += a.cheapest;
                mostExpensive += a.mostExpensive;
                if (a.farPct.size() >= minPairs) {
                    farMedians.add(median(a.farPct));
                }
                if (a.nearPct.size() >= minPairs) {
                    nearMedians.add(median(a.nearPct));
                }
            }
            if (pctMedians.size() < minDestinations) {
                continue;
            }
            Collections.sort(pooled);
            String code = airlineEntry.getKey();
            airlines.add(new Airline(code, AirlineNames.resolve(code), pctMedians.size(), comparisons,
                    median(pctMedians), percentile(pooled, 0.25), percentile(pooled, 0.75), median(rubMedians),
                    cheapest / (double) comparisons, mostExpensive / (double) comparisons,
                    farMedians.size() >= minDestinations ? median(farMedians) : null,
                    nearMedians.size() >= minDestinations ? median(nearMedians) : null));
        }
        airlines.sort(Comparator.comparingDouble(Airline::medianPct).thenComparingInt(a -> -a.comparisons()));

        // матрица «направление × авиакомпания» только для авиакомпаний, прошедших пороги
        Set<String> eligible = new HashSet<>();
        for (Airline a : airlines) {
            eligible.add(a.code());
        }
        for (var airlineEntry : acc.entrySet()) {
            if (!eligible.contains(airlineEntry.getKey())) {
                continue;
            }
            for (var destEntry : airlineEntry.getValue().entrySet()) {
                Acc a = destEntry.getValue();
                if (a.pct.size() >= minPairs) {
                    cells.computeIfAbsent(destEntry.getKey(), k -> new HashMap<>())
                            .put(airlineEntry.getKey(), new Cell(median(a.pct), a.pct.size()));
                }
            }
        }
    }

    /** Авиакомпании, прошедшие пороги, от самой дешёвой политики к самой дорогой. */
    public List<Airline> airlines() {
        return airlines;
    }

    /** Направления по убыванию числа сравнений (для строк матрицы). */
    public List<String> destinationsByComparisons() {
        List<String> result = new ArrayList<>(cells.keySet());
        result.sort(Comparator.comparingInt((String d) -> -cells.get(d).values().stream()
                .mapToInt(Cell::comparisons).sum()));
        return result;
    }

    public Cell cell(String destination, String airlineCode) {
        Map<String, Cell> row = cells.get(destination);
        return row == null ? null : row.get(airlineCode);
    }

    public int totalGroups() {
        return totalGroups;
    }

    public int minPairs() {
        return minPairs;
    }

    public int minDestinations() {
        return minDestinations;
    }

    public String diagnostics() {
        return "сравнений (направление + дата, где есть минимум две авиакомпании): " + totalGroups
                + "; авиакомпаний в них: " + totalAirlines
                + "; прошли пороги (не меньше " + minPairs + " сравнений на направление и "
                + minDestinations + " направлений): " + airlines.size();
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** Процентиль с линейной интерполяцией; список должен быть отсортирован. */
    static double percentile(List<Double> sorted, double p) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double index = p * (sorted.size() - 1);
        int lo = (int) Math.floor(index);
        int hi = (int) Math.ceil(index);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (index - lo);
    }
}