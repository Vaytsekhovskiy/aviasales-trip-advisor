package avia;

import avia.client.AeroDataBoxClient;
import avia.model.FlightBatch;
import avia.model.FlightRecord;
import avia.renderer.DelayHtmlRenderer;
import avia.service.DelayAnalyzer;
import avia.service.FlightDatasetPlan;
import avia.storage.CsvFlightStorage;
import avia.storage.FlightReader;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class DatasetCli {
    private DatasetCli() { }

    public static void run(String[] args) throws IOException, InterruptedException {
        boolean collect = args[0].equals("--delays-collect"), report = args[0].equals("--delays-report");
        int maxArgs = collect || report ? 5 : 4;
        if (args.length < (collect ? 4 : 3) || args.length > maxArgs) throw usage();
        LocalDate from = LocalDate.parse(args[1]), to = LocalDate.parse(args[2]);
        List<String> airports = args.length == maxArgs ? Arrays.stream(args[maxArgs - 1].split(",", -1))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).toList() : FlightDatasetPlan.AIRPORTS;
        List<FlightBatch> batches = FlightDatasetPlan.batches(from, to, airports);
        var storage = new CsvFlightStorage(Path.of("data", "flights"));
        if (report) {
            int min = args.length >= 4 ? Integer.parseInt(args[3]) : 30;
            FlightReader reader = storage;
            List<FlightRecord> rows = new ArrayList<>();
            int loaded = 0;
            for (FlightBatch batch : batches) {
                if (storage.exists(batch)) { rows.addAll(reader.read(batch)); loaded++; }
            }
            var result = new DelayAnalyzer().analyze(rows, from, to, airports, min);
            Path path = Path.of("reports", "delays_" + from + "_" + to + "_n" + min + ".html");
            new DelayHtmlRenderer().render(path, result, loaded, batches.size());
            System.out.printf(Locale.ROOT, "Report: %s%nBatches %d/%d; rows %d; duplicate IDs %d; missing IDs %d; analyzed departures %d%n",
                    path.toAbsolutePath(), loaded, batches.size(), rows.size(), result.duplicates(), result.missingIds(), result.flights().size());
            return;
        }
        int existing = 0, cached = 0, requests = 0;
        for (FlightBatch batch : batches) {
            if (storage.exists(batch)) existing++;
            else if (Files.isRegularFile(rawPath(batch))) cached++;
            else requests++;
        }
        long references = airports.stream().filter(a -> !Files.isRegularFile(Path.of("data", "reference", "airports", a + ".json"))).count();
        System.out.printf(Locale.ROOT, "Airports: %s; dates %s..%s%nBatches: %d; existing CSV: %d; reusable JSON: %d; new FIDS requests: %d; missing airport references: %d%n",
                airports, from, to, batches.size(), existing, cached, requests, references);
        if (!collect) return; // Dry run never initializes the API client.
        int limit = Integer.parseInt(args[3]);
        if (limit < 0 || requests > limit) throw new IllegalArgumentException("FIDS request count exceeds explicit limit " + limit + "; nothing requested");
        if (airports.stream().anyMatch(a -> !FlightDatasetPlan.ICAO.containsKey(a))) {
            throw new IllegalArgumentException("Batch collection supports SVO,LED,AER,OVB,SVX with known ICAO coverage codes");
        }
        // Sequential collection: stop at the first API/error response; no automatic retries.
        for (String airport : airports) {
            boolean needsApi = batches.stream().filter(b -> b.airport().equals(airport))
                    .anyMatch(b -> !storage.exists(b) && !Files.isRegularFile(rawPath(b)));
            if (needsApi) {
                var client = new AeroDataBoxClient(System.getenv("AERODATABOX_API_KEY"));
                JSONObject health = new JSONObject(client.health(FlightDatasetPlan.ICAO.get(airport)));
                String status = health.getJSONObject("liveFlightUpdatesFeed").getString("status");
                log("coverage", airport, null, status);
                System.out.println(airport + " liveFlightUpdatesFeed: " + status);
                if (!status.equals("OK")) throw new IOException("Coverage is not OK for " + airport + "; collection stopped");
            }
            DelayCli.run(new String[]{"--delays-airport", airport});
        }
        for (FlightBatch batch : batches) {
            if (storage.exists(batch)) { log("skipped", batch.airport(), batch, "CSV exists"); continue; }
            boolean network = !Files.isRegularFile(rawPath(batch));
            log("started", batch.airport(), batch, network ? "FIDS" : "cached JSON");
            try {
                DelayCli.run(new String[]{"--delays", batch.airport(), batch.date().toString(),
                        Integer.toString(batch.from().getHour()), batch.to().equals(LocalTime.MIDNIGHT) ? "24" : Integer.toString(batch.to().getHour())});
                log("saved", batch.airport(), batch, "CSV saved");
            } catch (IOException | RuntimeException e) {
                log("failed", batch.airport(), batch, e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    private static Path rawPath(FlightBatch b) { return Path.of("data", "raw", b.date().toString(), b.fileName().replace(".csv", ".json")); }
    private static void log(String event, String airport, FlightBatch batch, String detail) throws IOException {
        Path path = Path.of("data", "collection.jsonl");
        Files.createDirectories(path.getParent());
        JSONObject row = new JSONObject().put("at", Instant.now().toString()).put("event", event).put("airport", airport).put("detail", detail);
        if (batch != null) row.put("date", batch.date().toString()).put("batch", batch.fileName());
        Files.writeString(path, row + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage: --delays-plan FROM TO [SVO,LED,AER,OVB,SVX]\n"
                + "       --delays-collect FROM TO MAX_FIDS_REQUESTS [SVO,LED,AER,OVB,SVX]\n"
                + "       --delays-report FROM TO [MIN_SAMPLES] [SVO,LED,AER,OVB,SVX]");
    }
}
