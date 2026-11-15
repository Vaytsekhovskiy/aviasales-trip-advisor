package avia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Три простых столбчатых графика для задачи 2 (прямые и пересадочные рейсы):
 *   1. сколько билетов в каждой категории (0 / 1 / 2+ пересадок) — характеристика входных данных;
 *   2. на сколько процентов самый дешёвый пересадочный билет дороже (дешевле) самого дешёвого
 *      прямого на ту же дату — общий результат;
 *   3. то же по направлениям (десять направлений с наибольшим числом сравнений).
 */
public class TransferChartsRenderer {

    private static final String[] COLORS = {"#3987e5", "#d95926", "#199e70"};
    private static final String[] NAMES = {"Прямой", "1 пересадка", "2+ пересадки"};
    private static final int TOP_DESTINATIONS = 10;

    static final int LEFT = 76;
    static final int RIGHT = 12;
    static final int TOP = 24;
    static final int BOTTOM = 44;

    public static void render(Path outputFile, String origin, String currency, PairedTransferStats stats,
                              int[] ticketsByBucket, String period) throws IOException {
        int totalTickets = ticketsByBucket[0] + ticketsByBucket[1] + ticketsByBucket[2];

        // график 1: число билетов по категориям
        String chart1 = singleSeries("Число билетов по категориям",
                List.of(NAMES),
                List.of((double) ticketsByBucket[0], (double) ticketsByBucket[1], (double) ticketsByBucket[2]),
                List.of(fmt(ticketsByBucket[0]), fmt(ticketsByBucket[1]), fmt(ticketsByBucket[2])),
                List.of(COLORS), "Число билетов");

        // график 2: общий результат (медиана по направлениям)
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        List<String> valueTexts = new ArrayList<>();
        List<String> colors = new ArrayList<>();
        StringBuilder note = new StringBuilder();
        for (int i = 1; i <= 2; i++) {
            PairedTransferStats.Overall o = stats.overall(i);
            if (o == null) {
                continue;
            }
            labels.add(NAMES[i]);
            values.add(o.medianPct());
            valueTexts.add(String.format(Locale.ROOT, "%+.0f%%", o.medianPct()));
            colors.add(COLORS[i]);
            note.append(NAMES[i]).append(": медиана по ").append(o.directions()).append(" направлениям, сравнений ")
                    .append(o.pairs()).append(", в ").append(Math.round(o.shareCheaper() * 100))
                    .append("% из них пересадочный оказался дешевле прямого. ");
        }
        String chart2 = values.isEmpty()
                ? "<p class='empty'>Недостаточно данных: нужно не меньше " + stats.minPairs()
                + " дат с прямым и пересадочным билетом на направление.</p>"
                : singleSeries("На сколько % дороже прямого рейса", labels, values, valueTexts, colors, "% к цене прямого");

        // график 3: по направлениям
        List<PairedTransferStats.Destination> top = new ArrayList<>(stats.destinations());
        top.sort(Comparator.comparingInt(PairedTransferStats.Destination::pairs).reversed());
        if (top.size() > TOP_DESTINATIONS) {
            top = top.subList(0, TOP_DESTINATIONS);
        }
        String chart3 = top.isEmpty()
                ? "<p class='empty'>Нет направлений с достаточным числом сравнений.</p>"
                : groupedPremiums(top);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
                .append("<title>Прямые и пересадочные рейсы из ").append(esc(origin)).append("</title><style>")
                .append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#111;color:#eee;")
                .append("max-width:900px;margin:0 auto;padding:24px}")
                .append("h1{font-size:20px;margin:0 0 6px}h2{font-size:15px;margin:26px 0 4px}")
                .append(".sub{color:#c3c2b7;font-size:13px;line-height:1.5}")
                .append(".cap{color:#898781;font-size:12px;line-height:1.5;margin:2px 0 8px}")
                .append(".row{display:flex;flex-wrap:wrap;gap:24px}.row>div{flex:1 1 380px;min-width:300px}")
                .append(".legend{display:flex;flex-wrap:wrap;gap:16px;font-size:12px;color:#c3c2b7;margin:4px 0}")
                .append(".legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px}")
                .append(".empty{color:#898781;font-size:13px}")
                .append("</style></head><body>");
        html.append("<h1>Прямые и пересадочные рейсы из ").append(esc(origin)).append("</h1>");
        html.append("<div class='sub'>Источник: Travelpayouts prices_for_dates (кэш поисков Aviasales). Уникальных предложений: ")
                .append(totalTickets).append("; ").append(esc(period)).append(". Сравнение — по паре «направление + дата вылета»; ")
                .append("направление учитывается, если таких пар не меньше ").append(stats.minPairs()).append(".</div>");

        html.append("<div class='row'><div><h2>1. Сколько билетов в каждой категории</h2>")
                .append("<div class='cap'>Все направления из ").append(esc(origin)).append(" вместе.</div>")
                .append(chart1).append("</div>")
                .append("<div><h2>2. На сколько пересадки дороже прямого</h2>")
                .append("<div class='cap'>Сравниваются самые дешёвые билеты на одну дату в одном срезе. ")
                .append(esc(note.toString())).append("</div>")
                .append(chart2).append("</div></div>");

        html.append("<h2>3. На сколько дороже прямого, по направлениям</h2>")
                .append("<div class='cap'>Медиана по датам для десяти направлений с наибольшим числом сравнений. ")
                .append("Если столбца нет — меньше ").append(stats.minPairs())
                .append(" сравнений. Ниже нуля — пересадочный дешевле прямого.</div>")
                .append("<div class='legend'>");
        for (int i = 1; i <= 2; i++) {
            html.append("<span><i style='background:").append(COLORS[i]).append("'></i>").append(NAMES[i]).append("</span>");
        }
        html.append("</div>").append(chart3).append("</body></html>");

        Files.writeString(outputFile, html.toString(), StandardCharsets.UTF_8);
    }

    // ---------- один ряд столбцов (допускает отрицательные значения) ----------

    private static String singleSeries(String ariaLabel, List<String> labels, List<Double> values,
                                       List<String> valueTexts, List<String> colors, String yTitle) {
        int w = 420;
        int h = 290;
        int plotW = w - LEFT - RIGHT;
        int plotH = h - TOP - BOTTOM;

        double minV = 0;
        double maxV = 0;
        for (double v : values) {
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        Axis axis = new Axis(minV, maxV, TOP, plotH);
        boolean percent = yTitle.startsWith("%");

        StringBuilder svg = svgStart(w, h, ariaLabel);
        drawAxis(svg, axis, LEFT, plotW, percent);
        svg.append(text(14, TOP + plotH / 2.0, yTitle, "middle", "#c3c2b7", 11,
                "transform='rotate(-90 14 " + f1(TOP + plotH / 2.0) + ")'"));

        double slot = plotW / (double) values.size();
        double barW = Math.min(slot * 0.55, 90);
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i);
            double cx = LEFT + slot * (i + 0.5);
            double y0 = axis.y(0);
            double y1 = axis.y(v);
            svg.append("<rect x='").append(f1(cx - barW / 2)).append("' y='").append(f1(Math.min(y0, y1)))
                    .append("' width='").append(f1(barW)).append("' height='").append(f1(Math.max(Math.abs(y0 - y1), 1)))
                    .append("' fill='").append(colors.get(i)).append("' rx='3'/>");
            double labelY = v >= 0 ? y1 - 6 : y1 + 14;
            svg.append(text(cx, labelY, valueTexts.get(i), "middle", "#eee", 12, "font-weight='600'"));
            svg.append(text(cx, TOP + plotH + 20, labels.get(i), "middle", "#c3c2b7", 12, ""));
        }
        return svg.append("</svg>").toString();
    }

    // ---------- сгруппированные столбцы по направлениям (допускают отрицательные значения) ----------

    private static String groupedPremiums(List<PairedTransferStats.Destination> destinations) {
        int w = 860;
        int h = 340;
        int bottom = 56;
        int plotW = w - LEFT - RIGHT;
        int plotH = h - TOP - bottom;

        double minV = 0;
        double maxV = 0;
        for (PairedTransferStats.Destination d : destinations) {
            for (int i = 1; i <= 2; i++) {
                if (d.group(i) != null) {
                    minV = Math.min(minV, d.group(i).medianPct());
                    maxV = Math.max(maxV, d.group(i).medianPct());
                }
            }
        }
        Axis axis = new Axis(minV, maxV, TOP, plotH);

        StringBuilder svg = svgStart(w, h, "На сколько пересадочные рейсы дороже прямых по направлениям");
        drawAxis(svg, axis, LEFT, plotW, true);
        svg.append(text(14, TOP + plotH / 2.0, "% к цене прямого", "middle", "#c3c2b7", 11,
                "transform='rotate(-90 14 " + f1(TOP + plotH / 2.0) + ")'"));

        double slot = plotW / (double) destinations.size();
        double barW = Math.min(slot * 0.8 / 2, 40);
        for (int g = 0; g < destinations.size(); g++) {
            PairedTransferStats.Destination d = destinations.get(g);
            double groupLeft = LEFT + slot * (g + 0.5) - barW;
            for (int i = 1; i <= 2; i++) {
                PairedTransferStats.Group group = d.group(i);
                if (group == null) {
                    continue;
                }
                double v = group.medianPct();
                double y0 = axis.y(0);
                double y1 = axis.y(v);
                String tip = d.name() + " (" + d.code() + "): " + NAMES[i].toLowerCase(Locale.ROOT)
                        + String.format(Locale.ROOT, ", медиана %+.0f%%", v) + ", сравнений: " + group.pairs()
                        + ", пересадочный дешевле в " + Math.round(group.shareCheaper() * 100) + "% случаев";
                svg.append("<rect x='").append(f1(groupLeft + barW * (i - 1))).append("' y='").append(f1(Math.min(y0, y1)))
                        .append("' width='").append(f1(barW - 1)).append("' height='").append(f1(Math.max(Math.abs(y0 - y1), 1)))
                        .append("' fill='").append(COLORS[i]).append("' rx='2'><title>").append(esc(tip)).append("</title></rect>");
            }
            String label = d.name().length() > 10 ? d.name().substring(0, 9) + "…" : d.name();
            svg.append(text(LEFT + slot * (g + 0.5), TOP + plotH + 18, label, "middle", "#c3c2b7", 11, ""));
            if (!d.name().equals(d.code())) { // если названия нет, код уже показан строкой выше
                svg.append(text(LEFT + slot * (g + 0.5), TOP + plotH + 32, d.code(), "middle", "#898781", 10, ""));
            }
        }
        return svg.append("</svg>").toString();
    }

    // ---------- общие мелочи для рисования ----------

    /** Вертикальная шкала с «красивыми» делениями; всегда включает ноль. */
    static class Axis {
        final double lo;
        final double hi;
        final double step;
        final int top;
        final int height;

        Axis(double min, double max, int top, int height) {
            if (max - min <= 0) {
                max = min + 1;
            }
            double raw = (max - min) / 5;
            double mag = Math.pow(10, Math.floor(Math.log10(raw)));
            double f = raw / mag;
            this.step = (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * mag;
            this.lo = Math.floor(min / step) * step;
            this.hi = Math.ceil(max * 1.12 / step) * step; // запас сверху под подписи значений
            this.top = top;
            this.height = height;
        }

        double y(double v) {
            return top + height - (v - lo) / (hi - lo) * height;
        }
    }

    static void drawAxis(StringBuilder svg, Axis axis, int left, int plotW, boolean percent) {
        for (double t = axis.lo; t <= axis.hi + axis.step / 1000; t += axis.step) {
            double y = axis.y(t);
            boolean zero = Math.abs(t) < axis.step / 1000;
            svg.append("<line x1='").append(left).append("' y1='").append(f1(y)).append("' x2='").append(left + plotW)
                    .append("' y2='").append(f1(y)).append("' stroke='").append(zero ? "#898781" : "#2c2c2a").append("'/>");
            String label = percent ? String.format(Locale.ROOT, "%+.0f%%", t) : fmt((int) Math.round(t));
            svg.append(text(left - 8, y + 4, zero && percent ? "0%" : label, "end", "#898781", 11, ""));
        }
    }

    static StringBuilder svgStart(int w, int h, String ariaLabel) {
        return new StringBuilder("<svg viewBox='0 0 " + w + " " + h + "' width='100%' role='img' aria-label='"
                + esc(ariaLabel) + "'>");
    }

    static String text(double x, double y, String s, String anchor, String color, int size, String extra) {
        return "<text x='" + f1(x) + "' y='" + f1(y) + "' text-anchor='" + anchor + "' fill='" + color
                + "' font-size='" + size + "' " + extra + ">" + esc(s) + "</text>";
    }

    static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String fmt(int v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', '\u00A0');
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
}