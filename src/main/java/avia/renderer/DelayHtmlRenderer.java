package avia.renderer;

import avia.service.DelayAnalyzer;
import avia.service.DelayAnalyzer.Stats;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.DayOfWeek;
import java.util.*;

/** Standalone report: no CDN or network dependencies. Shared fixed scale across airports. */
public final class DelayHtmlRenderer {
    private static final String[] DAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    public void render(Path output, DelayAnalyzer.Result r, int loadedBatches, int expectedBatches) throws IOException {
        StringBuilder h = new StringBuilder("""
                <!doctype html><html lang="ru"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Задержки рейсов — исследовательский срез</title>
                <style>
                *{box-sizing:border-box}body{margin:0;background:#f3f5f8;color:#182538;font:15px/1.55 system-ui,sans-serif}
                main{max-width:1500px;margin:auto;padding:30px}h1{font-size:28px;margin-bottom:6px}h2{font-size:20px}
                .muted{color:#536276}.card{background:white;border:1px solid #dbe2eb;border-radius:12px;padding:20px;margin:20px 0}
                .scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
                th,td{border:1px solid #dbe2eb;padding:8px;text-align:right;white-space:nowrap}th{background:#eef2f7}
                th:first-child,td:first-child{text-align:left}.heat td{text-align:center;min-width:46px}.heat small{display:block;font-size:10px}
                .empty{background:#f3f5f8;color:#8491a2}.low{background:repeating-linear-gradient(135deg,#fff,#fff 5px,#eef2f7 5px,#eef2f7 7px);color:#536276}
                .swatch{display:inline-block;padding:3px 9px;margin:3px;border-radius:4px}select{padding:8px;font:inherit}
                a{color:#155ca0}summary{cursor:pointer;font-weight:600}footer{margin:30px 0;color:#536276;font-size:13px}
                </style></head><body><main><h1>Когда вылеты задерживаются</h1>
                """);
        h.append("<p class='muted'>").append(r.from()).append(" — ").append(r.to())
                .append(" · локальное время аэропорта вылета · медианная задержка, минуты</p>");
        if (r.minSamples() < 30) h.append("<p class='card'><b>Предварительный просмотр.</b> Порог ниже 30: ячейки могут отражать единичные рейсы. Не используйте эту карту для ранжирования аэропортов.</p>");
        h.append("<section class='card'><b>Качество выборки</b><p>CSV batches: ").append(loadedBatches).append(" / ").append(expectedBatches)
                .append(" · исходных строк: ").append(r.inputRows()).append(" · повторных ID: ").append(r.duplicates())
                .append(" · без ID (исключены): ").append(r.missingIds()).append(" · вне периода/аэропортов или без local departure: ").append(r.outOfScope())
                .append(" · рейсов в анализе: ").append(r.flights().size()).append(".</p>")
                .append("<p>Цвет и медиана показываются при N ≥ ").append(r.minSamples())
                .append(". Штриховка — мало наблюдений; прочерк — нет подходящих задержек. Это не нулевые значения.</p>")
                .append("<p>Дедупликация: последнее collected_at_utc; при равенстве — более полная запись, затем наблюдение вылета. ")
                .append("Для вылетов используются статусы Departed/Arrived, для прилётов и компенсации — только Arrived. ")
                .append("Отмены не входят в задержки. Revised time может оставаться оценкой API; статус не гарантирует фактическое время.</p></section>");
        h.append("<label for='airport'>Аэропорт: </label><select id='airport'><option value='all'>Все пять / вся выборка</option>");
        for (String airport : r.airports()) h.append("<option value='").append(escape(airport)).append("'>").append(escape(airport)).append("</option>");
        h.append("</select><p>");
        String[] labels = {"Раньше расписания", "0–15 мин", "&gt;15–30 мин", "&gt;30–60 мин", "&gt;60 мин"};
        int[] levels = {-5, 0, 30, 60, 120};
        for (int i = 0; i < levels.length; i++) h.append("<span class='swatch' style='background:").append(color(levels[i])).append("'>").append(labels[i]).append("</span>");
        h.append(" · шкала одинакова для всех аэропортов</p>");
        for (String airport : r.airports()) {
            Stats total = r.byAirport().get(airport);
            h.append("<section class='card airport' data-airport='").append(escape(airport)).append("'><h2>").append(escape(airport))
                    .append(" · рейсов: ").append(total.count()).append(" · задержек вылета: ").append(total.departureN()).append("</h2><div class='scroll'><table class='heat'><thead><tr><th>День / час</th>");
            for (int hour = 0; hour < 24; hour++) h.append("<th>").append(hour).append("</th>");
            h.append("</tr></thead><tbody>");
            for (DayOfWeek day : DayOfWeek.values()) {
                h.append("<tr><th>").append(DAYS[day.getValue()-1]).append("</th>");
                for (int hour = 0; hour < 24; hour++) {
                    Stats s = r.heatmap().get(DelayAnalyzer.cellKey(airport, day, hour));
                    if (s == null || s.departureN() == 0) { h.append("<td class='empty' title='Нет подходящих наблюдений задержки'>—</td>"); continue; }
                    if (s.departureMedian() == null) {
                        h.append("<td class='low' title='Недостаточно наблюдений: ").append(s.departureN()).append("'>·<small>N=").append(s.departureN()).append("</small></td>");
                    } else {
                        h.append("<td style='background:").append(color(s.departureMedian())).append("' title='N=").append(s.departureN())
                                .append("; задержка &gt;15 мин: ").append(percent(s.over15())).append("'>").append(number(s.departureMedian()))
                                .append("<small>N=").append(s.departureN()).append("</small></td>");
                    }
                }
                h.append("</tr>");
            }
            h.append("</tbody></table></div></section>");
        }
        table(h, "По аэропорту вылета", r.byAirport());
        table(h, "По авиакомпании", r.byAirline());
        table(h, "По направлению", r.byRoute());
        h.append("""
                <footer>Источник: AeroDataBox / RapidAPI. Окно запроса FIDS не равно диапазону scheduled departure.
                Фильтрация отчёта — по scheduled departure local. Недостающие batches и малые группы ограничивают сравнения.
                Доли &gt;15/30/60 рассчитаны только среди доступных задержек вылета, доля отмен — среди всех рейсов группы.
                Медианы прилёта и компенсации относятся к рейсам из указанного аэропорта, а не ко всем прибывшим в него рейсам.</footer>
                <script>document.getElementById('airport').addEventListener('change',function(){
                document.querySelectorAll('.airport').forEach(e=>e.hidden=this.value!=='all'&&e.dataset.airport!==this.value);});</script>
                </main></body></html>
                """);
        if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, h.toString(), StandardCharsets.UTF_8);
    }

    private static void table(StringBuilder h, String title, Map<String, Stats> groups) {
        h.append("<details class='card' open><summary>").append(escape(title)).append("</summary><div class='scroll'><table><tr>")
                .append("<th>Группа</th><th>Рейсов</th><th>N вылет</th><th>Медиана вылет</th><th>N прилёт</th><th>Медиана прилёт</th>")
                .append("<th>N компенсация</th><th>Медиана компенсация</th><th>&gt;15</th><th>&gt;30</th><th>&gt;60</th><th>Отмены</th></tr>");
        groups.forEach((key, s) -> h.append("<tr><td>").append(escape(key)).append("</td><td>").append(s.count())
                .append("</td><td>").append(s.departureN()).append("</td><td>").append(number(s.departureMedian()))
                .append("</td><td>").append(s.arrivalN()).append("</td><td>").append(number(s.arrivalMedian()))
                .append("</td><td>").append(s.recoveryN()).append("</td><td>").append(number(s.recoveryMedian()))
                .append("</td><td>").append(percent(s.over15())).append("</td><td>").append(percent(s.over30()))
                .append("</td><td>").append(percent(s.over60())).append("</td><td>").append(s.cancelled()).append(" / ")
                .append(s.count()).append(" (").append(percent(s.cancellationRate())).append(")</td></tr>"));
        h.append("</table></div></details>");
    }
    private static String color(double v) { return v < 0 ? "#c3e5ee" : v <= 15 ? "#e0ede0" : v <= 30 ? "#fff0c2" : v <= 60 ? "#ffd29c" : "#f5aaa0"; }
    private static String number(Double n) { return n == null ? "—" : String.format(Locale.ROOT, "%.1f", n); }
    private static String percent(Double n) { return n == null ? "—" : String.format(Locale.ROOT, "%.1f%%", n * 100); }
    private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
