package avia.task2.analysis;

import avia.common.price.DestinationNames;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Задача 2 (сравнение «на одну дату»): для каждой даты вылета в каждом срезе берётся
 * самый дешёвый прямой билет и самый дешёвый билет с пересадками, и считается, на сколько
 * процентов пересадочный дороже (или дешевле) прямого. Так сравниваются билеты на одни и те
 * же даты, и разница в спросе по датам не искажает результат.
 *
 * Одна «пара» = одно направление + одна дата вылета + один срез.
 */
public class PairedTransferStats {

    /** Минимум пар на направление по умолчанию (меняется параметром --min-pairs). */
    public static final int DEFAULT_MIN_PAIRS = 3;

    private static final int NO_PRICE = Integer.MAX_VALUE;

    /** Результат по одной категории (1 пересадка или 2+) для одного направления. */
    public record Group(double medianPct, int pairs, int cheaper) {
        /** Доля пар, где пересадочный оказался дешевле прямого. */
        public double shareCheaper() {
            return pairs == 0 ? 0 : cheaper / (double) pairs;
        }
    }

    public record Destination(String code, String name, Group oneStop, Group twoPlus) {
        /** 1 = одна пересадка, 2 = две и больше. */
        public Group group(int index) {
            return index == 1 ? oneStop : twoPlus;
        }

        public int pairs() {
            return (oneStop == null ? 0 : oneStop.pairs()) + (twoPlus == null ? 0 : twoPlus.pairs());
        }
    }

    public record Overall(double medianPct, double meanPct, int directions, int pairs, double shareCheaper) {
    }

    private final List<Destination> destinations = new ArrayList<>();
    private final int minPairs;

    // сколько пар нашлось всего, до применения порога — для диагностики
    private int pairsOneStop;
    private int pairsTwoPlus;
    private int destinationsOneStop;
    private int destinationsTwoPlus;

    /** Вход: направление -> список [минимальная цена прямого, 1 пересадки, 2+] по каждой паре дата+срез. */
    public PairedTransferStats(Map<String, List<int[]>> minsByDestination) {
        this(minsByDestination, DEFAULT_MIN_PAIRS);
    }

    public PairedTransferStats(Map<String, List<int[]>> minsByDestination, int minPairs) {
        this.minPairs = minPairs;
        for (var entry : minsByDestination.entrySet()) {
            List<Double> oneStop = new ArrayList<>();
            List<Double> twoPlus = new ArrayList<>();
            for (int[] m : entry.getValue()) {
                if (m[0] == NO_PRICE) {
                    continue; // в этот день нет прямого билета — сравнивать не с чем
                }
                if (m[1] != NO_PRICE) {
                    oneStop.add((m[1] - m[0]) * 100.0 / m[0]);
                }
                if (m[2] != NO_PRICE) {
                    twoPlus.add((m[2] - m[0]) * 100.0 / m[0]);
                }
            }
            pairsOneStop += oneStop.size();
            pairsTwoPlus += twoPlus.size();
            destinationsOneStop += oneStop.isEmpty() ? 0 : 1;
            destinationsTwoPlus += twoPlus.isEmpty() ? 0 : 1;
            Group g1 = summarize(oneStop, minPairs);
            Group g2 = summarize(twoPlus, minPairs);
            if (g1 != null || g2 != null) {
                String code = entry.getKey();
                destinations.add(new Destination(code, DestinationNames.resolve(code, null), g1, g2));
            }
        }
    }

    public List<Destination> destinations() {
        return destinations;
    }

    public int minPairs() {
        return minPairs;
    }

    /** Сколько сравнений нашлось до применения порога и сколько направлений его прошло. */
    public String diagnostics() {
        int passedOne = 0;
        int passedTwo = 0;
        for (Destination d : destinations) {
            passedOne += d.oneStop() == null ? 0 : 1;
            passedTwo += d.twoPlus() == null ? 0 : 1;
        }
        return "Сравнений «прямой и 1 пересадка»: " + pairsOneStop + " (направлений " + destinationsOneStop
                + ", прошли порог " + minPairs + ": " + passedOne + "); «прямой и 2+ пересадки»: " + pairsTwoPlus
                + " (направлений " + destinationsTwoPlus + ", прошли порог: " + passedTwo + ")";
    }

    /** Общий результат: медиана по направлениям из медиан каждого направления. */
    public Overall overall(int index) {
        List<Double> medians = new ArrayList<>();
        int pairs = 0;
        int cheaper = 0;
        for (Destination d : destinations) {
            Group g = d.group(index);
            if (g != null) {
                medians.add(g.medianPct());
                pairs += g.pairs();
                cheaper += g.cheaper();
            }
        }
        if (medians.isEmpty()) {
            return null;
        }
        double mean = medians.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return new Overall(median(medians), mean, medians.size(), pairs, cheaper / (double) pairs);
    }

    private static Group summarize(List<Double> premiums, int minPairs) {
        if (premiums.size() < minPairs) {
            return null;
        }
        int cheaper = 0;
        for (double p : premiums) {
            if (p < 0) {
                cheaper++;
            }
        }
        return new Group(median(premiums), premiums.size(), cheaper);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
