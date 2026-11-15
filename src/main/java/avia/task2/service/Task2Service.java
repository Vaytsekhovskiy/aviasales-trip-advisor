package avia.task2.service;

import avia.task2.analysis.PairedTransferStats;
import avia.task2.analysis.TransferStats;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Application boundary for direct-versus-transfer analysis. */
@Service
public class Task2Service {
    public TransferStats summarize(Map<String, Map<Integer, List<Integer>>> pricesByDestination) {
        return new TransferStats(pricesByDestination);
    }

    public PairedTransferStats compareMatchedDates(Map<String, List<int[]>> minimumPrices, int minPairs) {
        return new PairedTransferStats(minimumPrices, minPairs);
    }
}
