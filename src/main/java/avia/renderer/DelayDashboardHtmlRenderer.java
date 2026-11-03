package avia.renderer;

import avia.model.DelayDashboardData;
import avia.model.DelayDashboardData.Group;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Inline SVG/canvas report with no CDN, fonts, backend or other network dependencies. */
public final class DelayDashboardHtmlRenderer {
    public void render(Path output, DelayDashboardData data) throws IOException {
        String template;
        try (var stream = getClass().getResourceAsStream("/avia/delay-dashboard.html")) {
            if (stream == null) throw new IOException("Dashboard template resource missing");
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String json = json(data).toString().replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, template.replace("__DASHBOARD_DATA__", json), StandardCharsets.UTF_8);
    }

    private static JSONObject json(DelayDashboardData d) {
        JSONArray points = new JSONArray();
        for (var p : d.recovery().points()) points.put(new JSONArray().put(p.departure()).put(p.arrival()));
        var recovery = d.recovery();
        return new JSONObject().put("source", d.source()).put("total", group(d.total()))
                .put("airportCount", d.airportCount()).put("airlineCount", d.airlineCount())
                .put("from", nullable(d.from())).put("to", nullable(d.to()))
                .put("missingLocalDeparture", d.missingLocalDeparture())
                .put("heatmapMin", d.minHeatmapSample()).put("airlineMin", d.minAirlineSample())
                .put("weekdays", groups(d.weekdays())).put("heatmap", groups(d.heatmap()))
                .put("airports", groups(d.airports())).put("airlines", groups(d.airlines())).put("daily", groups(d.daily()))
                .put("recovery", new JSONObject().put("points", points).put("median", nullable(recovery.median()))
                        .put("recovered", nullable(recovery.recoveredShare())).put("worsened", nullable(recovery.worsenedShare()))
                        .put("unchanged", nullable(recovery.unchangedShare())).put("low", recovery.centralLow()).put("high", recovery.centralHigh()));
    }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    private static JSONArray groups(List<Group> groups) {
        JSONArray array = new JSONArray(); groups.forEach(g -> array.put(group(g))); return array;
    }
    private static JSONObject group(Group g) {
        var m = g.metrics();
        return new JSONObject().put("label", g.label()).put("n", g.departureN()).put("arrivalN", g.arrivalN())
                .put("flights", m.sampleSize()).put("median", nullable(m.medianDepartureDelayMin()))
                .put("mean", nullable(m.meanDepartureDelayMin())).put("p15", nullable(m.departureDelay15Rate()))
                .put("p30", nullable(m.departureDelay30Rate())).put("p60", nullable(m.departureDelay60Rate()))
                .put("cancellations", m.sampleSize() == 0 ? JSONObject.NULL : m.cancellationRate());
    }
}
