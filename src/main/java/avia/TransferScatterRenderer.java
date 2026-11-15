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
 * Один график вместо длинного списка: диаграмма рассеяния «цена прямого рейса — цена рейса
 * с пересадками» по направлениям. Каждая точка — направление, обе оси в логарифмической
 * шкале. Точка выше пунктирной линии (равная цена) — рейс с пересадками дороже прямого,
 * ниже — дешевле. Над графиком — короткая сводка по всей выборке.
 */
public class TransferScatterRenderer {

    private static final int W = 820;
    private static final int H = 580;
    private static final int LEFT = 85;
    private static final int RIGHT = 30;
    private static final int TOP = 20;
    private static final int BOTTOM = 70;

    private static final int LABELED_POINTS = 10; // подписываем кодом только самые «выбивающиеся» точки
    private static final String[] COLORS = {"#3987e5", "#d95926", "#199e70"};
    private static final String[] NAMES = {"Прямой рейс", "1 пересадка", "2+ пересадки"};
    private static final int[] TICKS = {1000, 2000, 3000, 5000, 10000, 20000, 30000, 50000,
            100000, 200000, 300000, 500000};

    private record Point(String code, String name, int bucketIndex,
                         TransferStats.Bucket direct, TransferStats.Bucket other, double pct) {
    }

    public static void render(Path outputFile, String origin, String currency, TransferStats stats,
                              int offers, String period) throws IOException {

        List<Point> points = new ArrayList<>();
        int withoutDirect = 0;
        int destinationsOnChart = 0;
        for (TransferStats.Destination d : stats.destinations()) {
            if (d.direct() == null) {
                withoutDirect++;
                continue;
            }
            boolean plotted = false;
            for (int i = 1; i <= 2; i++) {
                if (d.bucket(i) != null) {
                    points.add(new Point(d.code(), d.name(), i, d.direct(), d.bucket(i), d.premiumPct(i)));
                    plotted = true;
                }
            }
            if (plotted) {
                destinationsOnChart++;
            }
        }

        double min = Double.MAX_VALUE;
        double max = 0;
        for (Point p : points) {
            min = Math.min(min, Math.min(p.direct().median(), p.other().median()));
            max = Math.max(max, Math.max(p.direct().median(), p.other().median()));
        }
        if (points.isEmpty()) {
            min = 1000;
            max = 100000;
        }
        double lo = min * 0.85;
        double hi = max * 1.2;

        String cur = currency.toUpperCase();
        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox='0 0 ").append(W).append(' ').append(H)
                .append("' width='100%' role='img' aria-label='Цена прямого рейса и рейса с пересадками по направлениям'>");

        int plotW = W - LEFT - RIGHT;
        int plotH = H - TOP - BOTTOM;
        svg.append("<rect x='").append(LEFT).append("' y='").append(TOP).append("' width='").append(plotW)
                .append("' height='").append(plotH).append("' fill='#181818'/>");

        // сетка и подписи осей
        for (int tick : TICKS) {
            if (tick < lo || tick > hi) {
                continue;
            }
            double x = xPos(tick, lo, hi, plotW);
            double y = yPos(tick, lo, hi, plotH);
            svg.append(line(x, TOP, x, TOP + plotH, "#2c2c2a", ""));
            svg.append(line(LEFT, y, LEFT + plotW, y, "#2c2c2a", ""));
            svg.append(text(x, TOP + plotH + 16, fmt(tick), "middle", "#898781", 11, ""));
            svg.append(text(LEFT - 8, y + 4, fmt(tick), "end", "#898781", 11, ""));
        }
        svg.append(text(LEFT + plotW / 2.0, H - 18,
                "Медианная цена прямого рейса, " + cur + " (логарифмическая шкала)", "middle", "#c3c2b7", 12, ""));
        svg.append(text(20, TOP + plotH / 2.0,
                "Медианная цена рейса с пересадками, " + cur + " (логарифмическая шкала)", "middle", "#c3c2b7", 12,
                "transform='rotate(-90 20 " + f1(TOP + plotH / 2.0) + ")'"));

        // линия равной цены
        svg.append(line(xPos(lo, lo, hi, plotW), yPos(lo, lo, hi, plotH),
                xPos(hi, lo, hi, plotW), yPos(hi, lo, hi, plotH), "#898781", "stroke-dasharray='6 5'"));

        // подписи кодов у самых отклоняющихся от линии точек
        List<Point> toLabel = new ArrayList<>(points);
        toLabel.sort(Comparator.comparingDouble((Point p) -> Math.abs(p.pct())).reversed());
        List<Point> labeled = toLabel.subList(0, Math.min(LABELED_POINTS, toLabel.size()));

        for (Point p : points) {
            double cx = xPos(p.direct().median(), lo, hi, plotW);
            double cy = yPos(p.other().median(), lo, hi, plotH);
            String tip = p.name() + " (" + p.code() + ")\n"
                    + "прямой: " + fmt(p.direct().median()) + " " + cur + " (n=" + p.direct().count() + ")\n"
                    + NAMES[p.bucketIndex()].toLowerCase(Locale.ROOT) + ": " + fmt(p.other().median()) + " " + cur
                    + " (n=" + p.other().count() + ")\n"
                    + String.format(Locale.ROOT, "%+.0f%%", p.pct()) + " к цене прямого";
            svg.append("<circle cx='").append(f1(cx)).append("' cy='").append(f1(cy)).append("' r='5.5' fill='")
                    .append(COLORS[p.bucketIndex()]).append("' fill-opacity='0.85' stroke='#111'>")
                    .append("<title>").append(esc(tip)).append("</title></circle>");
            if (labeled.contains(p)) {
                svg.append(text(cx + 8, cy + 4, p.code(), "start", "#c3c2b7", 10, ""));
            }
        }
        if (points.isEmpty()) {
            svg.append(text(LEFT + plotW / 2.0, TOP + plotH / 2.0,
                    "Нет направлений, где есть и прямые, и пересадочные рейсы", "middle", "#898781", 14, ""));
        }
        svg.append("</svg>");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang='ru'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
                .append("<title>Прямые и пересадочные рейсы из ").append(esc(origin)).append("</title><style>")
                .append("body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#111;color:#eee;")
                .append("max-width:880px;margin:0 auto;padding:24px}")
                .append("h1{font-size:20px;margin:0 0 6px}")
                .append(".sub{color:#c3c2b7;font-size:13px;margin-bottom:16px;line-height:1.5}")
                .append(".sum{background:#1c1c1c;border-radius:8px;padding:12px 20px;margin:0 0 16px;font-size:14px;line-height:1.7;list-style:none}")
                .append(".legend{display:flex;flex-wrap:wrap;gap:18px;font-size:13px;color:#c3c2b7;margin-top:10px}")
                .append(".legend i{display:inline-block;width:11px;height:11px;border-radius:50%;margin-right:6px}")
                .append(".note{color:#898781;font-size:12px;margin-top:12px;line-height:1.5}")
                .append("</style></head><body>");
        html.append("<h1>Прямые и пересадочные рейсы из ").append(esc(origin)).append("</h1>");
        html.append("<div class='sub'>Источник: Travelpayouts prices_for_dates (кэш поисков Aviasales). ")
                .append("Уникальных предложений: ").append(offers).append("; ").append(esc(period)).append(". ")
                .append("Точка — направление, цена — медиана по категории (учитываются категории, где не меньше ")
                .append(TransferStats.MIN_SAMPLES_PER_BUCKET).append(" билетов).</div>");

        html.append("<ul class='sum'>");
        for (int i = 1; i <= 2; i++) {
            TransferStats.Overall o = stats.overall(i);
            html.append("<li><b style='color:").append(COLORS[i]).append("'>").append(NAMES[i]).append("</b>: ");
            if (o == null) {
                html.append("недостаточно данных для общего вывода");
            } else {
                html.append("относительно прямого рейса в среднем <b>")
                        .append(String.format(Locale.ROOT, "%+.0f%%", o.meanPct())).append("</b>, медиана ")
                        .append(String.format(Locale.ROOT, "%+.0f%%", o.medianPct()))
                        .append(", направлений: ").append(o.directions());
            }
            html.append("</li>");
        }
        html.append("</ul>");

        html.append(svg);
        html.append("<div class='legend'>")
                .append("<span><i style='background:").append(COLORS[1]).append("'></i>1 пересадка</span>")
                .append("<span><i style='background:").append(COLORS[2]).append("'></i>2+ пересадки</span>")
                .append("<span>- - - цена пересадочного равна цене прямого</span></div>");
        html.append("<div class='note'>Точка выше пунктирной линии — рейс с пересадками дороже прямого на этом направлении, ")
                .append("ниже — дешевле. Направлений на графике: ").append(destinationsOnChart)
                .append("; без прямых рейсов (не показаны): ").append(withoutDirect)
                .append(". Подписаны коды десяти направлений с самым большим отличием, остальные — во всплывающей подсказке при наведении.</div>");
        html.append("</body></html>");

        Files.writeString(outputFile, html.toString(), StandardCharsets.UTF_8);
    }

    // логарифмическая шкала: одинаковые границы по обеим осям, поэтому линия равной цены идёт под 45°
    private static double xPos(double v, double lo, double hi, int plotW) {
        return LEFT + (Math.log(v) - Math.log(lo)) / (Math.log(hi) - Math.log(lo)) * plotW;
    }

    private static double yPos(double v, double lo, double hi, int plotH) {
        return TOP + plotH - (Math.log(v) - Math.log(lo)) / (Math.log(hi) - Math.log(lo)) * plotH;
    }

    private static String line(double x1, double y1, double x2, double y2, String color, String extra) {
        return "<line x1='" + f1(x1) + "' y1='" + f1(y1) + "' x2='" + f1(x2) + "' y2='" + f1(y2)
                + "' stroke='" + color + "' " + extra + "/>";
    }

    private static String text(double x, double y, String s, String anchor, String color, int size, String extra) {
        return "<text x='" + f1(x) + "' y='" + f1(y) + "' text-anchor='" + anchor + "' fill='" + color
                + "' font-size='" + size + "' " + extra + ">" + esc(s) + "</text>";
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt(int v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', '\u00A0');
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
