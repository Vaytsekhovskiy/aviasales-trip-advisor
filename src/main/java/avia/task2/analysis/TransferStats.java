package avia.task2.analysis;

import avia.common.price.DestinationNames;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Задача 2: для каждого направления считает медиану, среднее и число билетов
 * в категориях «прямой» (0), «1 пересадка», «2+ пересадки» и на сколько процентов
 * рейс с пересадками дороже (или дешевле) прямого.
 *
 * Логика такая же, как в вашем Python-скрипте: категория учитывается, только если
 * в ней не меньше MIN_SAMPLES_PER_BUCKET билетов.
 */
public class TransferStats {

    public static final int MIN_SAMPLES_PER_BUCKET = 3;

    public record Bucket(int median, int mean, int count) {
    }

    public record Destination(String code, String name, Bucket direct, Bucket oneStop, Bucket twoPlus) {

        /** 0 = прямой, 1 = одна пересадка, 2 = две и больше. */
        public Bucket bucket(int index) {
            return index == 0 ? direct : index == 1 ? oneStop : twoPlus;
        }

        /** Сравнивать можно, если заполнены хотя бы две категории. */
        public boolean comparable() {
            int filled = 0;
            for (int i = 0; i < 3; i++) {
                if (bucket(i) != null) {
                    filled++;
                }
            }
            return filled >= 2;
        }

        /** На сколько % категория дороже прямого рейса (null, если нет данных для сравнения). */
        public Double premiumPct(int index) {
            Bucket b = bucket(index);
            if (index == 0 || b == null || direct == null) {
                return null;
            }
            return (b.median() - direct.median()) * 100.0 / direct.median();
        }
    }

    public record Overall(double meanPct, double medianPct, int directions) {
    }

    private final List<Destination> destinations = new ArrayList<>();

    /** Вход: направление -> (категория 0/1/2 -> цены билетов). */
    public TransferStats(Map<String, Map<Integer, List<Integer>>> pricesByDestination) {
        for (var entry : pricesByDestination.entrySet()) {
            String code = entry.getKey();
            Map<Integer, List<Integer>> byBucket = entry.getValue();
            destinations.add(new Destination(code, DestinationNames.resolve(code, null),
                    summarize(byBucket.get(0)), summarize(byBucket.get(1)), summarize(byBucket.get(2))));
        }
    }

    public List<Destination> destinations() {
        return destinations;
    }

    /** Сводка по всем направлениям, где есть и прямой рейс, и выбранная категория. */
    public Overall overall(int bucketIndex) {
        List<Double> values = new ArrayList<>();
        for (Destination d : destinations) {
            Double pct = d.premiumPct(bucketIndex);
            if (pct != null) {
                values.add(pct);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        Collections.sort(values);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        int n = values.size();
        double median = n % 2 == 1 ? values.get(n / 2) : (values.get(n / 2 - 1) + values.get(n / 2)) / 2;
        return new Overall(mean, median, n);
    }

    private static Bucket summarize(List<Integer> prices) {
        if (prices == null || prices.size() < MIN_SAMPLES_PER_BUCKET) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(prices);
        Collections.sort(sorted);
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        double mean = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new Bucket((int) Math.round(median), (int) Math.round(mean), n);
    }
}
