package avia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Locale;

public class HeatmapHtmlRenderer {

    private static final String[] MONTH_NAMES = {
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн",
            "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"
    };

    public static void render(Path outputFile,
                               String origin,
                               String destination,
                               String currency,
                               SeasonalityAggregator agg) throws IOException {

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int m = 1; m <= 12; m++) {
            for (DayOfWeek dow : SeasonalityAggregator.WEEK_ORDER) {
                var cell = agg.get(m, dow);
                if (cell.isEmpty()) continue;
                min = Math.min(min, (int) cell.avgPrice());
                max = Math.max(max, (int) cell.avgPrice());
            }
        }
        if (min > max) { min = 0; max = 1; } // защита от пустых данных

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'>");
        html.append("<title>Сезонность цен ").append(origin).append(" → ").append(destination).append("</title>");
        html.append("<style>");
        html.append("body{font-family:Arial,sans-serif;background:#111;color:#eee;padding:24px}");
        html.append("h1{font-size:20px}");
        html.append("table{border-collapse:collapse;margin-top:16px}");
        html.append("th,td{padding:10px 14px;text-align:center;border:1px solid #333;font-size:13px}");
        html.append("th{background:#1c1c1c}");
        html.append(".empty{background:#2a2a2a;color:#777}");
        html.append(".legend{margin-top:14px;font-size:12px;color:#aaa}");
        html.append("</style></head><body>");
        html.append("<h1>Сезонность цен: ").append(origin).append(" → ").append(destination)
            .append(" (средняя цена, ").append(currency.toUpperCase()).append(")</h1>");
        html.append("<table><tr><th>Месяц \\ День недели</th>");
        for (DayOfWeek dow : SeasonalityAggregator.WEEK_ORDER) {
            html.append("<th>").append(capitalize(dow.getDisplayName(TextStyle.SHORT, new Locale("ru")))).append("</th>");
        }
        html.append("</tr>");

        for (int m = 1; m <= 12; m++) {
            html.append("<tr><th>").append(MONTH_NAMES[m - 1]).append("</th>");
            for (DayOfWeek dow : SeasonalityAggregator.WEEK_ORDER) {
                var cell = agg.get(m, dow);
                if (cell.isEmpty()) {
                    html.append("<td class='empty'>—</td>");
                } else {
                    String color = colorFor(cell.avgPrice(), min, max);
                    html.append("<td style='background:").append(color).append("' title='n=")
                        .append(cell.sampleCount()).append("'>")
                        .append(Math.round(cell.avgPrice())).append("</td>");
                }
            }
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("<p class='legend'>Зелёный — дешевле, красный — дороже. Число в ячейке — средняя цена по всем найденным датам этого месяца/дня недели за ближайший год. Источник: Travelpayouts /v1/prices/calendar (кэш поисков Aviasales).</p>");
        html.append("</body></html>");

        Files.writeString(outputFile, html.toString(), StandardCharsets.UTF_8);
    }

    public static void renderCheapestDestinations(Path outputFile,
                                                  String origin,
                                                  String currency,
                                                  CheapestDestinationAggregator agg) throws IOException {

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int m = 1; m <= 12; m++) {
            for (DayOfWeek dow : SeasonalityAggregator.WEEK_ORDER) {
                var cell = agg.get(m, dow);
                if (cell.isEmpty()) continue;
                min = Math.min(min, (int) cell.avgPrice());
                max = Math.max(max, (int) cell.avgPrice());
            }
        }
        if (min > max) { min = 0; max = 1; }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'>");
        html.append("<title>Куда дешевле лететь из ").append(origin).append("</title>");
        html.append("<style>");
        html.append("body{font-family:Arial,sans-serif;background:#111;color:#eee;padding:24px}");
        html.append("h1{font-size:20px}");
        html.append("table{border-collapse:collapse;margin-top:16px}");
        html.append("th,td{padding:10px 14px;text-align:center;border:1px solid #333;font-size:13px}");
        html.append("th{background:#1c1c1c}");
        html.append(".empty{background:#2a2a2a;color:#777}");
        html.append(".legend{margin-top:14px;font-size:12px;color:#aaa}");
        html.append("</style></head><body>");
        html.append("<h1>Куда дешевле лететь из ").append(origin)
                .append(" (").append(currency.toUpperCase()).append(")</h1>");
        html.append("<table><tr><th>Месяц \\ День недели</th>");
        for (DayOfWeek dow : SeasonalityAggregator.WEEK_ORDER) {
            html.append("<th>").append(capitalize(dow.getDisplayName(TextStyle.SHORT, new Locale("ru")))).append("</th>");
        }
        html.append("</tr>");

        for (int m = 1; m <= 12; m++) {
            html.append("<tr><th>").append(MONTH_NAMES[m - 1]).append("</th>");
            for (DayOfWeek dow : SeasonalityAggregator.WEEK_ORDER) {
                var cell = agg.get(m, dow);
                if (cell.isEmpty()) {
                    html.append("<td class='empty'>—</td>");
                } else {
                    String color = colorFor(cell.avgPrice(), min, max);
                    html.append("<td style='background:").append(color).append("' title='n=")
                            .append(cell.sampleCount()).append("'>")
                            .append(cell.destination()).append(" — ").append(cell.destinationName())
                            .append(" (").append(Math.round(cell.avgPrice())).append(")")
                            .append("</td>");
                }
            }
            html.append("</tr>");
        }
        html.append("</table>");
        html.append("<p class='legend'>Зелёный — дешевле, красный — дороже. В ячейке: направление с минимальной средней ценой для данного месяца и дня недели. Источник: Travelpayouts /v1/prices/calendar (кэш поисков Aviasales).</p>");
        html.append("</body></html>");

        Files.writeString(outputFile, html.toString(), StandardCharsets.UTF_8);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Линейная интерполяция между зелёным (дёшево) и красным (дорого) в HSL. */
    private static String colorFor(double value, int min, int max) {
        double t = max == min ? 0.5 : (value - min) / (double) (max - min);
        t = Math.max(0, Math.min(1, t));
        int hue = (int) Math.round(120 * (1 - t)); // 120=зелёный -> 0=красный
        return "hsl(" + hue + ",70%,35%)";
    }
}
