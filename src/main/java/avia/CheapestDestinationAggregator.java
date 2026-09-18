package avia;

import java.time.DayOfWeek;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Для каждой ячейки [месяц][день недели] выбирает направление
 * с минимальной средней ценой.
 */
public class CheapestDestinationAggregator {

    public record Cell(String destination, double avgPrice, int sampleCount) {
        public boolean isEmpty() {
            return destination == null || destination.isBlank() || sampleCount == 0;
        }
    }

    private final Cell[][] matrix = new Cell[12][7];

    public CheapestDestinationAggregator(List<PriceEntry> entries) {
        @SuppressWarnings("unchecked")
        Map<String, DestinationStats>[][] buckets = new Map[12][7];
        for (int m = 0; m < 12; m++) {
            for (int d = 0; d < 7; d++) {
                buckets[m][d] = new HashMap<>();
            }
        }

        for (PriceEntry e : entries) {
            if (e.destination() == null || e.destination().isBlank() || e.price() <= 0) {
                continue;
            }
            int monthIdx = e.monthIndex() - 1;
            int dayIdx = weekdayIndex(e.dayOfWeek());
            buckets[monthIdx][dayIdx]
                    .computeIfAbsent(e.destination(), __ -> new DestinationStats())
                    .add(e.price());
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

    private static Cell summarize(Map<String, DestinationStats> statsByDestination) {
        if (statsByDestination.isEmpty()) {
            return new Cell(null, 0, 0);
        }
        return statsByDestination.entrySet().stream()
                .map(e -> new Cell(e.getKey(), e.getValue().avg(), e.getValue().count))
                .min(Comparator.comparingDouble(Cell::avgPrice))
                .orElse(new Cell(null, 0, 0));
    }

    private static int weekdayIndex(DayOfWeek dow) {
        for (int i = 0; i < SeasonalityAggregator.WEEK_ORDER.length; i++) {
            if (SeasonalityAggregator.WEEK_ORDER[i] == dow) return i;
        }
        throw new IllegalStateException("Unknown weekday: " + dow);
    }

    private static class DestinationStats {
        private long sum;
        private int count;

        void add(int price) {
            sum += price;
            count++;
        }

        double avg() {
            return count == 0 ? 0 : sum / (double) count;
        }
    }
}
