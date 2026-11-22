package avia;

import avia.model.DelayDashboardData;
import avia.renderer.DelayDashboardHtmlRenderer;
import avia.service.DelayVisualizationAggregator;
import avia.storage.MongoFlightReader;
import java.nio.file.Path;
import java.util.List;

/** Read-only Mongo flights -> standalone local report. */
public final class MongoDelayDashboardCli {
    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println("Usage: MongoDelayDashboardCli [output.html] [minHeatmapSample] [minAirlineSample]");
            System.exit(1);
        }
        if (System.getenv("MONGO_URI") == null || System.getenv("MONGO_URI").isBlank()) {
            System.err.println("MONGO_URI is required in this Run Configuration / process environment.");
            System.exit(1);
        }
        try {
            Path output = Path.of(args.length > 0 ? args[0] : "output/delay-dashboard.html");
            int heat = args.length > 1 ? Integer.parseInt(args[1]) : DelayVisualizationAggregator.MIN_HEATMAP_SAMPLE;
            int airline = args.length > 2 ? Integer.parseInt(args[2]) : DelayVisualizationAggregator.MIN_AIRLINE_SAMPLE;
            var aggregator = new DelayVisualizationAggregator(heat, airline);
            DelayDashboardData data;
            try (var reader = new MongoFlightReader()) {
                System.out.println("Reading MongoDB avia.flights (read-only)...");
                var flights = reader.readAll();
                System.out.println("Flights loaded: " + flights.size() + "; calculating visualization groups...");
                data = aggregator.aggregate(flights);
            }
            new DelayDashboardHtmlRenderer().render(output, data);
            printSummary(data);
            System.out.println("\nDashboard created:\n" + output.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Dashboard generation failed (" + e.getClass().getSimpleName()
                    + "). Check MONGO_URI, Mongo document types, thresholds and output path permissions.");
            System.exit(1);
        }
    }

    public static void printSummary(DelayDashboardData data) {
        System.out.println("\nDelay visualization\nSource: " + data.source());
        System.out.println("Flights read: " + data.total().metrics().sampleSize());
        System.out.println("Valid departure delays: " + data.total().departureN());
        System.out.println("Valid arrival delays: " + data.total().arrivalN());
        System.out.println("Airports: " + data.airportCount() + "\nAirlines: " + data.airlineCount());
        System.out.println("Period: " + data.from() + ".." + data.to());
        System.out.println("Missing scheduled departure local: " + data.missingLocalDeparture());
        System.out.println("Thresholds: heatmap=" + data.minHeatmapSample() + ", airline=" + data.minAirlineSample());
        printGroups("Weekdays", data.weekdays());
        printGroups("Airports", data.airports());
        printGroups("Top airlines", data.airlines());
    }
    private static void printGroups(String title, List<DelayDashboardData.Group> groups) {
        System.out.println(title + " (N = valid departure delays):");
        groups.forEach(g -> System.out.println("  " + g.label() + ": N=" + g.departureN()
                + ", median=" + g.metrics().medianDepartureDelayMin() + ", mean=" + g.metrics().meanDepartureDelayMin()
                + ", P>30=" + g.metrics().departureDelay30Rate()));
    }
}
