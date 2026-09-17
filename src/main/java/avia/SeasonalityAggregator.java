package avia;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;

/**
 * Группирует цены в матрицу [месяц 1..12][день недели Пн..Вс]
 * и считает среднюю и медианную цену в каждой ячейке.
 */
public class SeasonalityAggregator {

    public static final DayOfWeek[] WEEK_ORDER = {
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
    };

    public record Cell(double avgPrice, int medianPrice, int sampleCount) {
        public boolean isEmpty() { return sampleCount == 0; }
    }

    private final Cell[][] matrix = new Cell[12][7]; // [месяц-1][день недели индекс]

    @SuppressWarnings("unchecked")
    public SeasonalityAggregator(List<PriceEntry> entries) {
        List<Integer>[][] buckets = new List[12][7];
        for (int m = 0; m < 12; m++) {
            for (int d = 0; d < 7; d++) {
                buckets[m][d] = new ArrayList<>();
            }
        }

        for (PriceEntry e : entries) {
            int monthIdx = e.monthIndex() - 1;
            int dayIdx = weekdayIndex(e.dayOfWeek());
            buckets[monthIdx][dayIdx].add(e.price());
        }

        for (int m = 0; m < 12; m++) {
            for (int d = 0; d < 7; d++) {
                matrix[m][d] = summarize(buckets[m][d]);
            }
        }
    }

    public Cell get(int monthIndex1to12, DayOfWeek dow) {
        return matrix[monthIndex1to12 - 1][weekdayIndex(dow)];
    }

    private static int weekdayIndex(DayOfWeek dow) {
        for (int i = 0; i < WEEK_ORDER.length; i++) {
            if (WEEK_ORDER[i] == dow) return i;
        }
        throw new IllegalStateException("Unknown weekday: " + dow);
    }

    private static Cell summarize(List<Integer> prices) {
        if (prices.isEmpty()) {
            return new Cell(0, 0, 0);
        }
        List<Integer> sorted = new ArrayList<>(prices);
        sorted.sort(Integer::compareTo);
        double avg = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        int median = sorted.get(sorted.size() / 2);
        return new Cell(avg, median, sorted.size());
    }
}
