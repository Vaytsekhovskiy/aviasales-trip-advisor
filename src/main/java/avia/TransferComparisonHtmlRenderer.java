package avia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Рендерит сравнение "прямой рейс vs рейс с пересадками" по направлениям
 * в виде горизонтальной столбчатой диаграммы (3 категории на направление)
 * плюс полная таблица цифр под ней.
 */
public class TransferComparisonHtmlRenderer {

    // Категориальная палитра, провалидированная под тёмный фон (см. dataviz skill):
    // 3 смежные пары дают worst-case CVD ΔE 9.4, обычное зрение ΔE 26.5, контраст >=3:1 на #111.
    private static final String COLOR_DIRECT = "#3987e5";     // синий — 0 пересадок
    private static final String COLOR_ONE_STOP = "#d95926";   // оранжевый — 1 пересадка
    private static final String COLOR_TWO_STOPS = "#199e70";  // бирюзовый — 2 пересадки
    private static final String COLOR_SUCCESS_TEXT = "#0ca30c";

    private static final int MAX_BAR_WIDTH_PX = 420;

    public static void render(Path outputFile,
                               String origin,
                               String currency,
                               List<TransferComparisonAggregator.Comparison> comparisons) throws IOException {

        int maxPrice = 1;
        for (var c : comparisons) {
            maxPrice = Math.max(maxPrice, nz(c.directPrice()));
            maxPrice = Math.max(maxPrice, nz(c.oneStopPrice()));
            maxPrice = Math.max(maxPrice, nz(c.twoStopPrice()));
        }

        String currencyUpper = currency.toUpperCase();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'>");
        html.append("<title>Прямые рейсы vs рейсы с пересадками из ").append(origin).append("</title>");
        html.append("<style>");
        html.append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#111;color:#eee;padding:24px}");
        html.append("h1{font-size:20px;margin-bottom:4px}");
        html.append(".subtitle{color:#c3c2b7;font-size:13px;margin-bottom:18px}");
        html.append(".legend{display:flex;gap:18px;margin-bottom:18px;font-size:13px;color:#c3c2b7}");
        html.append(".legend span{display:inline-flex;align-items:center;gap:6px}");
        html.append(".swatch{width:12px;height:12px;border-radius:2px;display:inline-block}");
        html.append(".row{margin-bottom:14px;padding-bottom:14px;border-bottom:1px solid #2c2c2a}");
        html.append(".row-head{display:flex;justify-content:space-between;align-items:baseline;margin-bottom:6px}");
        html.append(".dest{font-size:14px;font-weight:600}");
        html.append(".dest .code{color:#898781;font-weight:400;font-size:12px;margin-left:6px}");
        html.append(".badge{font-size:12px}");
        html.append(".badge.good{color:").append(COLOR_SUCCESS_TEXT).append("}");
        html.append(".badge.neutral{color:#898781}");
        html.append(".bar-line{display:flex;align-items:center;gap:8px;height:22px;margin:3px 0}");
        html.append(".bar-label{width:78px;font-size:11px;color:#898781;flex-shrink:0}");
        html.append(".bar-track{position:relative;height:16px}");
        html.append(".bar{height:16px;border-radius:4px;min-width:2px}");
        html.append(".bar-value{font-size:12px;color:#eee;margin-left:8px;font-variant-numeric:tabular-nums}");
        html.append(".empty-hint{font-size:11px;color:#5a5a57}");
        html.append("table{border-collapse:collapse;margin-top:28px;width:100%;font-size:12px}");
        html.append("th,td{padding:8px 12px;text-align:right;border:1px solid #2c2c2a;font-variant-numeric:tabular-nums}");
        html.append("th{background:#1c1c1c;color:#c3c2b7;text-align:right}");
        html.append("td.name{text-align:left;font-variant-numeric:normal}");
        html.append("th.name{text-align:left}");
        html.append(".dash{color:#5a5a57}");
        html.append(".legend-note{margin-top:14px;font-size:12px;color:#898781}");
        html.append("</style></head><body>");

        html.append("<h1>Прямые рейсы vs рейсы с пересадками: из ").append(origin).append("</h1>");
        html.append("<div class='subtitle'>Цены в ").append(currencyUpper)
                .append(" · направления отсортированы по выгоде от пересадки (сверху — где пересадка экономит больше всего)</div>");

        html.append("<div class='legend'>")
                .append("<span><i class='swatch' style='background:").append(COLOR_DIRECT).append("'></i>Прямой рейс (0 пересадок)</span>")
                .append("<span><i class='swatch' style='background:").append(COLOR_ONE_STOP).append("'></i>1 пересадка</span>")
                .append("<span><i class='swatch' style='background:").append(COLOR_TWO_STOPS).append("'></i>2 пересадки</span>")
                .append("</div>");

        for (var c : comparisons) {
            html.append("<div class='row'>");
            html.append("<div class='row-head'>");
            html.append("<div class='dest'>").append(escape(c.destinationName()))
                    .append("<span class='code'>").append(c.destination()).append("</span></div>");
            html.append(badgeHtml(c, currencyUpper));
            html.append("</div>");

            appendBar(html, "Прямой", c.directPrice(), maxPrice, COLOR_DIRECT, c.destinationName(), currencyUpper);
            appendBar(html, "1 перес.", c.oneStopPrice(), maxPrice, COLOR_ONE_STOP, c.destinationName(), currencyUpper);
            appendBar(html, "2 перес.", c.twoStopPrice(), maxPrice, COLOR_TWO_STOPS, c.destinationName(), currencyUpper);

            html.append("</div>");
        }

        html.append("<table><tr>")
                .append("<th class='name'>Направление</th>")
                .append("<th>Прямой</th><th>1 пересадка</th><th>2 пересадки</th>")
                .append("<th>Дешевле с пересадкой</th><th>Разница</th><th>Разница, %</th>")
                .append("</tr>");
        for (var c : comparisons) {
            html.append("<tr>");
            html.append("<td class='name'>").append(escape(c.destinationName())).append(" (").append(c.destination()).append(")</td>");
            html.append(priceCell(c.directPrice()));
            html.append(priceCell(c.oneStopPrice()));
            html.append(priceCell(c.twoStopPrice()));
            html.append(priceCell(c.cheapestConnectingPrice()));
            html.append(c.markupAbs() == null ? "<td class='dash'>—</td>" : "<td>" + signed(c.markupAbs()) + "</td>");
            html.append(c.markupPct() == null ? "<td class='dash'>—</td>" : "<td>" + signed(Math.round(c.markupPct())) + "%</td>");
            html.append("</tr>");
        }
        html.append("</table>");

        html.append("<p class='legend-note'>Источник: Travelpayouts /v1/prices/cheap (кэш поисков Aviasales за последние ~48 часов), ")
                .append("destination=\"-\" — по всем направлениям из ").append(origin)
                .append(" одним запросом. Для части направлений данные по какой-то категории пересадок могут отсутствовать в кэше — такие бары/ячейки помечены прочерком.</p>");

        html.append("</body></html>");

        Files.writeString(outputFile, html.toString(), StandardCharsets.UTF_8);
    }

    private static String badgeHtml(TransferComparisonAggregator.Comparison c, String currencyUpper) {
        Integer markup = c.markupAbs();
        if (markup == null) {
            return "<span class='badge neutral'>недостаточно данных для сравнения</span>";
        }
        if (markup > 0) {
            return "<span class='badge good'>пересадка дешевле на " + markup + " " + currencyUpper
                    + " (" + Math.round(c.markupPct()) + "%)</span>";
        }
        if (markup < 0) {
            return "<span class='badge neutral'>прямой рейс дешевле на " + (-markup) + " " + currencyUpper
                    + " (" + Math.round(-c.markupPct()) + "%)</span>";
        }
        return "<span class='badge neutral'>цена одинакова</span>";
    }

    private static void appendBar(StringBuilder html, String label, Integer price, int maxPrice,
                                   String color, String destinationName, String currencyUpper) {
        html.append("<div class='bar-line'>");
        html.append("<span class='bar-label'>").append(label).append("</span>");
        if (price == null) {
            html.append("<span class='empty-hint'>нет данных в кэше</span>");
        } else {
            int width = Math.max(2, Math.round(price * (float) MAX_BAR_WIDTH_PX / maxPrice));
            html.append("<div class='bar-track' style='width:").append(MAX_BAR_WIDTH_PX).append("px'>");
            html.append("<div class='bar' style='width:").append(width).append("px;background:").append(color)
                    .append("' title='").append(escape(destinationName)).append(" — ").append(label)
                    .append(": ").append(price).append(" ").append(currencyUpper).append("'></div>");
            html.append("</div>");
            html.append("<span class='bar-value'>").append(price).append("</span>");
        }
        html.append("</div>");
    }

    private static String priceCell(Integer price) {
        return price == null ? "<td class='dash'>—</td>" : "<td>" + price + "</td>";
    }

    private static String signed(long value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
