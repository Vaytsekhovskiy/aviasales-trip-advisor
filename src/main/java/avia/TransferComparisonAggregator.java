package avia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Группирует предложения /v1/prices/cheap по направлению и сравнивает
 * цену прямого рейса с самой дешёвой пересадочной альтернативой.
 */
public class TransferComparisonAggregator {

    public record Comparison(
            String destination,
            String destinationName,
            Integer directPrice,
            Integer oneStopPrice,
            Integer twoStopPrice) {

        /** Самая дешёвая цена среди рейсов с пересадками (1 или 2), если она есть. */
        public Integer cheapestConnectingPrice() {
            if (oneStopPrice == null) return twoStopPrice;
            if (twoStopPrice == null) return oneStopPrice;
            return Math.min(oneStopPrice, twoStopPrice);
        }

        /** На сколько прямой рейс дороже самой дешёвой пересадки (может быть отрицательным). */
        public Integer markupAbs() {
            Integer connecting = cheapestConnectingPrice();
            if (directPrice == null || connecting == null) return null;
            return directPrice - connecting;
        }

        /** То же самое в процентах от цены пересадочного рейса. */
        public Double markupPct() {
            Integer connecting = cheapestConnectingPrice();
            Integer markup = markupAbs();
            if (markup == null || connecting == 0) return null;
            return markup * 100.0 / connecting;
        }

        public boolean hasComparison() {
            return markupAbs() != null;
        }
    }

    private final List<Comparison> comparisons;

    public TransferComparisonAggregator(List<TransferOffer> offers) {
        Map<String, Integer[]> pricesByDestination = new LinkedHashMap<>();
        Map<String, String> namesByDestination = new LinkedHashMap<>();

        for (TransferOffer o : offers) {
            Integer[] prices = pricesByDestination.computeIfAbsent(o.destination(), d -> new Integer[3]);
            prices[o.transfers()] = o.price();
            namesByDestination.putIfAbsent(o.destination(), o.destinationName());
        }

        List<Comparison> list = new ArrayList<>();
        for (var entry : pricesByDestination.entrySet()) {
            Integer[] p = entry.getValue();
            list.add(new Comparison(entry.getKey(), namesByDestination.get(entry.getKey()), p[0], p[1], p[2]));
        }

        list.sort((a, b) -> {
            Double pa = a.markupPct();
            Double pb = b.markupPct();
            if (pa == null && pb == null) return 0;
            if (pa == null) return 1;
            if (pb == null) return -1;
            return Double.compare(pb, pa); // по убыванию: сначала где пересадка выгоднее всего
        });

        this.comparisons = List.copyOf(list);
    }

    public List<Comparison> comparisons() {
        return comparisons;
    }
}
