package avia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static avia.TransferChartsRenderer.BOTTOM;
import static avia.TransferChartsRenderer.LEFT;
import static avia.TransferChartsRenderer.RIGHT;
import static avia.TransferChartsRenderer.TOP;
import static avia.TransferChartsRenderer.drawAxis;
import static avia.TransferChartsRenderer.esc;
import static avia.TransferChartsRenderer.f1;
import static avia.TransferChartsRenderer.fmt;
import static avia.TransferChartsRenderer.svgStart;
import static avia.TransferChartsRenderer.text;

/**
 * Один HTML-отчёт по задаче 4 для нескольких городов вылета. Для каждого города (и для всех
 * городов вместе) своя вкладка со следующими блоками:
 *   1. на сколько процентов авиакомпания дороже самой дешёвой на то же направление и дату;
 *   2. в какой доле сравнений авиакомпания самая дешёвая;
 *   3. таблица подробной статистики по всем авиакомпаниям;
 *   4. матрица «направление × авиакомпания» — кто дешевле на каком направлении.
 */
public class AirlineChartsRenderer {

    private static final int TOP_AIRLINES = 12;        // столбцов на графиках
    private static final int MATRIX_AIRLINES = 10;     // столбцов в матрице
    private static final int MATRIX_DESTINATIONS = 15; // строк в матрице

    /** id — латиница без пробелов (код города или "all"). */
    public record Section(String id, String title, AirlineStats stats, String period) {
    }

    public static void render(Path outputFile, List<Section> sections, boolean directOnly) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
                .append("<title>Ценовая политика авиакомпаний</title><style>")
                .append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#111;color:#eee;")
                .append("max-width:960px;margin:0 auto;padding:24px}")
                .append("h1{font-size:20px;margin:0 0 6px}h2{font-size:15px;margin:28px 0 4px}")
                .append(".sub{color:#c3c2b7;font-size:13px;line-height:1.5;margin-bottom:6px}")
                .append(".cap{color:#898781;font-size:12px;line-height:1.5;margin:2px 0 8px}")
                .append(".empty{color:#898781;font-size:13px}")
                .append(".tabs{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 4px}")
                .append(".tab{background:#1c1c1c;color:#c3c2b7;border:1px solid #2c2c2a;border-radius:6px;")
                .append("padding:6px 12px;font-size:13px;cursor:pointer}")
                .append(".tab.active{background:#3987e5;color:#fff;border-color:#3987e5}")
                .append(".scroll{overflow-x:auto}")
                .append("table{border-collapse:collapse;font-size:12px;width:100%}")
                .append("th{color:#898781;font-weight:500;text-align:right;padding:6px 8px;border-bottom:1px solid #2c2c2a;white-space:nowrap}")
                .append("td{padding:6px 8px;text-align:right;border-bottom:1px solid #1f1f1e;font-variant-numeric:tabular-nums}")
                .append("th:first-child,td:first-child{text-align:left}")
                .append("td small,th small{color:#898781;font-weight:400}")
                .append(".mx td{text-align:center;min-width:56px}.mx th{text-align:center}")
                .append(".mx td.na{color:#444}.mx td.best{font-weight:700;outline:2px solid #eee;outline-offset:-2px}")
                .append(".mx td:first-child{white-space:nowrap}")
                .append("</style></head><body>");

        html.append("<h1>Ценовая политика авиакомпаний на одинаковых направлениях</h1>");
        html.append("<div class='sub'>Источник: Travelpayouts prices_for_dates (кэш поисков Aviasales). ")
                .append(directOnly ? "Только прямые рейсы." : "Все рейсы, включая с пересадками.")
                .append("</div>");

        if (sections.size() > 1) {
            html.append("<div class='tabs'>");
            for (int i = 0; i < sections.size(); i++) {
                Section s = sections.get(i);
                html.append("<button class='tab").append(i == 0 ? " active" : "").append("' data-id='")
                        .append(esc(s.id())).append("'>").append(esc(s.title())).append("</button>");
            }
            html.append("</div>");
        }
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            html.append("<div class='section' id='sec-").append(esc(s.id())).append("' style='display:")
                    .append(i == 0 ? "block" : "none").append("'>");
            if (sections.size() > 1) {
                html.append("<h2 style='font-size:17px'>").append(esc(s.title())).append("</h2>");
            }
            html.append(sectionHtml(s));
            html.append("</div>");
        }
        html.append("<script>")
                .append("function show(id){document.querySelectorAll('.section').forEach(function(e){")
                .append("e.style.display=e.id==='sec-'+id?'block':'none';});")
                .append("document.querySelectorAll('.tab').forEach(function(b){")
                .append("b.classList.toggle('active',b.dataset.id===id);});}")
                .append("document.querySelectorAll('.tab').forEach(function(b){")
                .append("b.addEventListener('click',function(){show(b.dataset.id);});});")
                .append("</script></body></html>");

        Files.writeString(outputFile, html.toString(), StandardCharsets.UTF_8);
    }

    private static String sectionHtml(Section section) {
        AirlineStats stats = section.stats();
        List<AirlineStats.Airline> shown = top(stats.airlines(), TOP_AIRLINES);

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='sub'>Сравнений: ").append(stats.totalGroups()).append("; ").append(esc(section.period()))
                .append(". Авиакомпании учитываются, если у них не меньше ").append(stats.minPairs())
                .append(" сравнений на направление и не меньше ").append(stats.minDestinations()).append(" направлений.</div>");

        sb.append("<h2>1. На сколько авиакомпания дороже самой дешёвой</h2>")
                .append("<div class='cap'>Сравниваются самые дешёвые билеты разных авиакомпаний на одно направление и одну дату. ")
                .append("0% — авиакомпания была самой дешёвой. По направлению берётся медиана по датам, по авиакомпании — ")
                .append("медиана по направлениям, поэтому популярные направления не перевешивают остальные. ")
                .append("На графиках до ").append(TOP_AIRLINES).append(" авиакомпаний с наибольшим числом сравнений.</div>")
                .append(bars("На сколько % дороже самой дешёвой авиакомпании", shown,
                        AirlineStats.Airline::medianPct,
                        a -> String.format(Locale.ROOT, "%+.0f%%", a.medianPct()),
                        a -> a.name() + " (" + a.code() + "): медиана " + String.format(Locale.ROOT, "%+.0f%%", a.medianPct())
                                + " к самой дешёвой, направлений: " + a.destinations() + ", сравнений: " + a.comparisons(),
                        "#d95926", "% к самой дешёвой авиакомпании", true));

        sb.append("<h2>2. Как часто авиакомпания самая дешёвая</h2>")
                .append("<div class='cap'>Доля сравнений, в которых у авиакомпании была наименьшая цена. ")
                .append("Читайте с поправкой на число конкурентов: при двух авиакомпаниях случайное значение 50%, при трёх — 33%.</div>")
                .append(bars("Доля сравнений, где авиакомпания самая дешёвая", shown,
                        a -> a.cheapestShare() * 100,
                        a -> Math.round(a.cheapestShare() * 100) + "%",
                        a -> a.name() + " (" + a.code() + "): самой дешёвой в " + Math.round(a.cheapestShare() * 100)
                                + "% сравнений, самой дорогой в " + Math.round(a.mostExpensiveShare() * 100) + "%",
                        "#199e70", "% сравнений", false));

        sb.append("<h2>3. Подробная статистика</h2>")
                .append("<div class='cap'>Разброс — 25-й и 75-й процентили надбавки по всем сравнениям авиакомпании: ")
                .append("чем шире, тем менее предсказуема её цена относительно конкурентов. «Заранее» — билеты, снятые за ")
                .append(AirlineStats.NEAR_DAYS).append(" дней до вылета и раньше, «близко» — менее чем за ")
                .append(AirlineStats.NEAR_DAYS).append(" дней. Прочерк — данных недостаточно (или выбран режим --all-snapshots, ")
                .append("где срок до вылета неизвестен).</div>")
                .append(table(stats.airlines()));

        sb.append("<h2>4. Кто дешевле на каком направлении</h2>")
                .append("<div class='cap'>Медианная надбавка к самой дешёвой авиакомпании по каждому направлению. ")
                .append("Зелёный — дешевле всех, оранжевый — дороже на 50% и больше; жирная рамка — самая дешёвая авиакомпания ")
                .append("на этом направлении; точка — меньше ").append(stats.minPairs()).append(" сравнений.</div>")
                .append(matrix(stats));
        return sb.toString();
    }

    // ---------- столбчатый график ----------

    private static String bars(String ariaLabel, List<AirlineStats.Airline> list,
                               ToDoubleFunction<AirlineStats.Airline> value,
                               Function<AirlineStats.Airline, String> valueText,
                               Function<AirlineStats.Airline, String> tip,
                               String color, String yTitle, boolean percentAxis) {
        int w = 860;
        int h = 330;
        int bottom = Math.max(BOTTOM, 62);
        int plotW = w - LEFT - RIGHT;
        int plotH = h - TOP - bottom;

        double maxV = 0;
        for (AirlineStats.Airline a : list) {
            maxV = Math.max(maxV, value.applyAsDouble(a));
        }
        TransferChartsRenderer.Axis axis = new TransferChartsRenderer.Axis(0, maxV, TOP, plotH);

        StringBuilder svg = svgStart(w, h, ariaLabel);
        drawAxis(svg, axis, LEFT, plotW, percentAxis);
        svg.append(text(14, TOP + plotH / 2.0, yTitle, "middle", "#c3c2b7", 11,
                "transform='rotate(-90 14 " + f1(TOP + plotH / 2.0) + ")'"));

        double slot = plotW / (double) list.size();
        double barW = Math.min(slot * 0.6, 56);
        for (int i = 0; i < list.size(); i++) {
            AirlineStats.Airline a = list.get(i);
            double v = value.applyAsDouble(a);
            double cx = LEFT + slot * (i + 0.5);
            double y0 = axis.y(0);
            double y1 = axis.y(v);
            svg.append("<rect x='").append(f1(cx - barW / 2)).append("' y='").append(f1(Math.min(y0, y1)))
                    .append("' width='").append(f1(barW)).append("' height='").append(f1(Math.max(Math.abs(y0 - y1), 1)))
                    .append("' fill='").append(color).append("' rx='3'><title>").append(esc(tip.apply(a)))
                    .append("</title></rect>");
            svg.append(text(cx, y1 - 6, valueText.apply(a), "middle", "#eee", 12, "font-weight='600'"));
            svg.append(text(cx, TOP + plotH + 18, shorten(a.name(), 11), "middle", "#c3c2b7", 11, ""));
            if (!a.name().equals(a.code())) { // если названия нет, код уже показан строкой выше
                svg.append(text(cx, TOP + plotH + 32, a.code(), "middle", "#898781", 10, ""));
            }
        }
        return svg.append("</svg>").toString();
    }

    // ---------- таблица подробной статистики ----------

    private static String table(List<AirlineStats.Airline> airlines) {
        StringBuilder sb = new StringBuilder("<div class='scroll'><table><thead><tr>")
                .append("<th>Авиакомпания</th>")
                .append(th("Направл.", "Число направлений, на которых авиакомпания участвует в сравнениях"))
                .append(th("Сравн.", "Число сравнений (направление + дата)"))
                .append(th("Медиана", "Медианная надбавка к самой дешёвой авиакомпании, %"))
                .append(th("Разброс", "25-й — 75-й процентиль надбавки, %"))
                .append(th("Переплата", "Медианная переплата относительно самой дешёвой авиакомпании, ₽"))
                .append(th("Самая дешёвая", "Доля сравнений, где авиакомпания самая дешёвая"))
                .append(th("Самая дорогая", "Доля сравнений, где авиакомпания самая дорогая"))
                .append(th("Заранее", "Медианная надбавка для билетов за " + AirlineStats.NEAR_DAYS + " дней до вылета и раньше"))
                .append(th("Близко", "Медианная надбавка для билетов менее чем за " + AirlineStats.NEAR_DAYS + " дней до вылета"))
                .append("</tr></thead><tbody>");
        for (AirlineStats.Airline a : airlines) {
            sb.append("<tr><td>").append(esc(a.name()));
            if (!a.name().equals(a.code())) {
                sb.append(" <small>").append(esc(a.code())).append("</small>");
            }
            sb.append("</td><td>").append(a.destinations())
                    .append("</td><td>").append(a.comparisons())
                    .append("</td><td>").append(pct(a.medianPct()))
                    .append("</td><td>").append(pct(a.p25Pct())).append(" … ").append(pct(a.p75Pct()))
                    .append("</td><td>").append(fmt((int) Math.round(a.medianRub()))).append(" ₽")
                    .append("</td><td>").append(Math.round(a.cheapestShare() * 100)).append("%")
                    .append("</td><td>").append(Math.round(a.mostExpensiveShare() * 100)).append("%")
                    .append("</td><td>").append(a.farPct() == null ? "—" : pct(a.farPct()))
                    .append("</td><td>").append(a.nearPct() == null ? "—" : pct(a.nearPct()))
                    .append("</td></tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    private static String th(String title, String tooltip) {
        return "<th title='" + esc(tooltip) + "'>" + esc(title) + "</th>";
    }

    // ---------- матрица «направление × авиакомпания» ----------

    private static String matrix(AirlineStats stats) {
        List<AirlineStats.Airline> columns = top(stats.airlines(), MATRIX_AIRLINES);
        List<String> rows = stats.destinationsByComparisons();
        if (rows.size() > MATRIX_DESTINATIONS) {
            rows = rows.subList(0, MATRIX_DESTINATIONS);
        }
        if (columns.isEmpty() || rows.isEmpty()) {
            return "<p class='empty'>Недостаточно данных для матрицы.</p>";
        }

        StringBuilder sb = new StringBuilder("<div class='scroll'><table class='mx'><thead><tr><th></th>");
        for (AirlineStats.Airline a : columns) {
            sb.append("<th title='").append(esc(a.name())).append("'>").append(esc(a.code()));
            if (!a.name().equals(a.code())) {
                sb.append("<br><small>").append(esc(shorten(a.name(), 10))).append("</small>");
            }
            sb.append("</th>");
        }
        sb.append("</tr></thead><tbody>");

        for (String destination : rows) {
            double best = Double.MAX_VALUE;
            for (AirlineStats.Airline a : columns) {
                AirlineStats.Cell cell = stats.cell(destination, a.code());
                if (cell != null) {
                    best = Math.min(best, cell.medianPct());
                }
            }
            sb.append("<tr><td>").append(esc(label(destination))).append("</td>");
            for (AirlineStats.Airline a : columns) {
                AirlineStats.Cell cell = stats.cell(destination, a.code());
                if (cell == null) {
                    sb.append("<td class='na'>·</td>");
                    continue;
                }
                boolean isBest = cell.medianPct() == best;
                sb.append("<td").append(isBest ? " class='best'" : "").append(" style='background:")
                        .append(color(cell.medianPct())).append("' title='")
                        .append(esc(a.name() + ": " + pct(cell.medianPct()) + " к самой дешёвой, сравнений: " + cell.comparisons()))
                        .append("'>").append(pct(cell.medianPct())).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</tbody></table></div>").toString();
    }

    /** От зелёного (0%) к оранжевому (50% и больше). */
    private static String color(double pct) {
        double t = Math.min(Math.max(pct / 50.0, 0), 1);
        int r = (int) Math.round(25 + (217 - 25) * t);
        int g = (int) Math.round(158 + (89 - 158) * t);
        int b = (int) Math.round(112 + (38 - 112) * t);
        return "rgba(" + r + "," + g + "," + b + ",0.55)";
    }

    // ---------- мелочи ----------

    /** Берёт n авиакомпаний с наибольшим числом сравнений и возвращает их от дешёвой политики к дорогой. */
    private static List<AirlineStats.Airline> top(List<AirlineStats.Airline> airlines, int n) {
        List<AirlineStats.Airline> result = new ArrayList<>(airlines);
        result.sort(Comparator.comparingInt(AirlineStats.Airline::comparisons).reversed());
        if (result.size() > n) {
            result = new ArrayList<>(result.subList(0, n));
        }
        result.sort(Comparator.comparingDouble(AirlineStats.Airline::medianPct));
        return result;
    }

    /** "MOW-IST" -> "Москва → Стамбул", "IST" -> "Стамбул". */
    private static String label(String key) {
        int dash = key.indexOf('-');
        if (dash < 0) {
            return DestinationNames.resolve(key, null);
        }
        return DestinationNames.resolve(key.substring(0, dash), null) + " → "
                + DestinationNames.resolve(key.substring(dash + 1), null);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%+.0f%%", v);
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}