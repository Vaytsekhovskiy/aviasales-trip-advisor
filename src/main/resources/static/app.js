"use strict";

const panel = document.querySelector("#panel");
const tasks = new Map([
  [1, "Сезонность цен и дешёвые направления"],
  [2, "Прямые рейсы и пересадки"],
  [3, "Исторический анализ задержек"],
  [4, "Цены авиакомпаний"],
  [5, "Прогноз цены и задержки"]
]);
const weekdays = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const days = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const months = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"];
const stops = ["Прямые", "1 пересадка", "2+ пересадки"];
const stages = { QUEUED: "В очереди", COLLECTING: "Сбор", PREPROCESSING: "Подготовка",
  ANALYZING: "Анализ", COMPLETED: "Завершено", FAILED: "Ошибка" };
const state = new Map([...tasks.keys()].map(id => [id, { mode: "results", filters: {}, data: null, loading: false, error: "" }]));
const tables = new Map();
let task = 1;

const escaped = text => String(text ?? "").replace(/[&<>"']/g, character =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
const nf = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 0 });
const df = new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 1 });
const n = value => Number.isFinite(value) ? nf.format(value) : "—";
const amount = value => Number.isFinite(value) ? nf.format(value) + "\u00a0₽" : "—";
const minutes = value => Number.isFinite(value) ? df.format(value) + " мин" : "—";
const percent = value => Number.isFinite(value) ? df.format(value) + "%" : "—";
const share = value => Number.isFinite(value) ? percent(value * 100) : "—";
const weekday = value => days[weekdays.indexOf(value)] ?? value;
const unavailable = "По выбранным параметрам данных пока нет.";

function date(value) {
  return value ? value.slice(0, 10).split("-").reverse().join(".") : "—";
}

function departure(value) {
  const offset = value.match(/(?:[+-]\d\d:\d\d|Z)$/)?.[0];
  return `${date(value)} в ${value.slice(11, 16)} (UTC${offset === "Z" ? "+00:00" : offset ?? ""})`;
}

async function api(url, options) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => null);
  if (!response.ok || body === null) {
    throw new Error(body?.detail ?? "Не удалось получить ответ приложения. Проверьте, что backend запущен.");
  }
  return body;
}

const post = (url, body) => api(url, {
  method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)
});

const notice = (text, kind = "") => `<p class="notice ${kind}" role="${kind === "error" ? "alert" : "status"}">${escaped(text)}</p>`;
const conclusion = text => `<p class="conclusion">${escaped(text)}</p>`;
const kpis = items => `<div class="kpis">${items.map(([label, value]) =>
  `<div class="kpi"><strong>${escaped(value)}</strong><span>${escaped(label)}</span></div>`).join("")}</div>`;
const card = (title, description, content) => `<section class="card"><div class="card-heading">
  <h2>${escaped(title)}</h2><p>${escaped(description)}</p></div>${content}</section>`;
const axisNames = (x, y) => `<p class="axes"><span><strong>X</strong> ${escaped(x)}</span>
  <span><strong>Y</strong> ${escaped(y)}</span></p>`;

function bars(title, x, y, sample, series, format, comment) {
  const values = series.map(row => row.value).filter(Number.isFinite);
  if (!values.length) return card(title, sample, axisNames(x, y) + conclusion(comment));
  const lower = Math.min(0, ...values);
  const upper = Math.max(0, ...values);
  const span = upper - lower || 1;
  const zero = 100 * -lower / span;
  const rows = series.map(({ label, value, sample }) => {
    const shown = Number.isFinite(value);
    const fill = shown ? `<span class="bar-fill ${value < 0 ? "negative" : ""}"
      style="left:${100 * (Math.min(0, value) - lower) / span}%;width:${100 * Math.abs(value) / span}%"></span>` : "";
    return `<div class="bar-row" title="${escaped([label, format(value), sample].filter(Boolean).join(" · "))}">
      <span class="bar-label">${escaped(label)}</span><span class="bar-track" aria-hidden="true">
      <span class="bar-zero" style="left:${zero}%"></span>${fill}</span>
      <span class="bar-value">${escaped(format(value))}${sample ? ` <small class="hint">${escaped(sample)}</small>` : ""}</span></div>`;
  }).join("");
  return card(title, sample, axisNames(x, y)
    + `<div class="plot scroll chart-scroll"><div class="bars">${rows}</div></div>` + conclusion(comment));
}

function heatmap(title, sample, x, y, columns, rows, unit, format, comment, hours = false) {
  const values = rows.flatMap(row => row.cells.map(cell => cell?.value)).filter(Number.isFinite);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const cells = rows.map(row => `<tr><th scope="row">${escaped(row.label)}</th>${row.cells.map(cell => {
    if (!Number.isFinite(cell?.value)) return '<td title="Недостаточно наблюдений">—</td>';
    const intensity = max === min ? .36 : (cell.value - min) / (max - min);
    const lightness = 96 - intensity * 55;
    return `<td data-value style="--heat:hsl(214 67% ${lightness}%);--heat-text:${lightness < 57 ? "white" : "#102a43"}"
      title="${escaped(cell.details ?? "N=" + n(cell.count))}">${escaped(format(cell.value))}
      ${cell.label ? `<small>${escaped(cell.label)}</small>` : ""}</td>`;
  }).join("")}</tr>`).join("");
  const legend = values.length ? `<div class="legend">${escaped(format(min))}<span class="legend-gradient"></span>${escaped(format(max))} · ${escaped(unit)}</div>` : "";
  return card(title, sample, axisNames(x, y) + `<div class="plot scroll"><table class="heatmap ${hours ? "hours" : ""}" aria-label="${escaped(title)}">
    <thead><tr><th scope="col">${escaped(y)}</th>${columns.map(label => `<th scope="col">${escaped(label)}</th>`).join("")}</tr></thead>
    <tbody>${cells}</tbody></table></div>${legend}` + conclusion(comment));
}

function table(title, headers, rows, comment) {
  const id = "table-" + tables.size;
  const shown = 12;
  tables.set(id, { rows, shown });
  return card(title, `Строк в выборке: ${n(rows.length)}`, rows.length ?
    `<div class="scroll"><table class="data-table" aria-label="${escaped(title)}">
      <thead><tr>${headers.map(label => `<th scope="col">${escaped(label)}</th>`).join("")}</tr></thead>
      <tbody id="${id}">${tableRows(rows.slice(0, shown))}</tbody></table></div>
      ${rows.length > shown ? `<div class="table-controls"><button class="button secondary" data-table="${id}" type="button">Показать ещё</button></div>` : ""}
      ${conclusion(comment)}` : conclusion(comment));
}

function tableRows(rows) {
  return rows.map(row => `<tr>${row.map(cell => `<td>${escaped(cell)}</td>`).join("")}</tr>`).join("");
}

function period(snapshots) {
  return snapshots.count ? `${n(snapshots.count)} срезов. Сбор с ${date(snapshots.from)} по ${date(snapshots.to)}.`
    : "В выбранном периоде срезов нет.";
}

function seasonality(data) {
  if (!data.cheapestSampleCount) return notice(unavailable);
  const available = data.seasonality.filter(cell => cell.price.sampleCount);
  const cheapest = data.cheapest.filter(cell => cell.price.sampleCount);
  const low = [...available].sort((a, b) => a.price.medianPrice - b.price.medianPrice)[0];
  const deal = [...cheapest].sort((a, b) => a.price.avgPrice - b.price.avgPrice)[0];
  const source = data.source === "calendar" ? "Календарь Aviasales" : "Цены на конкретные даты";
  function grid(dataset, project) {
    return months.map((label, index) => ({
      label, cells: dataset.slice(index * 7, (index + 1) * 7).map(cell => cell.price.sampleCount ? project(cell.price) : null)
    }));
  }
  const lowText = low ? `Минимальная медианная цена — ${amount(low.price.medianPrice)}. ${months[low.month - 1]}, ${weekday(low.weekday).toLowerCase()}, N=${n(low.price.sampleCount)}.` : unavailable;
  const dealText = deal ? `Самая низкая средняя цена — ${amount(deal.price.avgPrice)} на маршруте ${data.origin} → ${deal.price.destination}. ${months[deal.month - 1]}, ${weekday(deal.weekday).toLowerCase()}, N=${n(deal.price.sampleCount)}.` : unavailable;
  return kpis([["Наблюдения сезонности", n(data.sampleCount)], ["Ценовые срезы", n(data.snapshots.count)], ["Источник", source]])
    + `<div class="columns seasonality-grid">${heatmap("Медианная цена по месяцу и дню недели",
      `N=${n(data.sampleCount)} пар «направление и дата вылета». ${period(data.snapshots)}`,
      "День недели", "Месяц вылета", days, grid(data.seasonality, price => ({
        value: price.medianPrice, count: price.sampleCount, details: `Средняя ${amount(price.avgPrice)} · N=${n(price.sampleCount)}`
      })), "рубли", amount, lowText)}
      ${heatmap("Самое дешёвое направление в каждом периоде",
        `N=${n(data.cheapestSampleCount)} пар «направление и дата вылета» из ${data.origin}.`,
        "День недели", "Месяц вылета", days, grid(data.cheapest, price => ({
          value: price.avgPrice, count: price.sampleCount, label: price.destination,
          details: `${price.destinationName ?? price.destination} · N=${n(price.sampleCount)}`
        })), "рубли", amount, dealText)}</div>`;
}

function transfers(data) {
  const offers = data.offerCounts.reduce((total, count) => total + count, 0);
  if (!offers) return notice(unavailable);
  const matched = [data.oneStop, data.twoPlus];
  const categories = [1, 2].filter(index => matched[index - 1]);
  const compare = categories.map(index => {
    const stat = matched[index - 1];
    const relation = stat.medianPct === 0 ? "на уровне прямых" :
      `на ${percent(Math.abs(stat.medianPct))} ${stat.medianPct > 0 ? "дороже" : "дешевле"} прямых`;
    return `${stops[index]} — ${relation} по медиане`;
  }).join(". ");
  const routeText = data.perSnapshot ? "На одинаковых датах вылета внутри одного среза." : "На одинаковых датах вылета по минимумам всех срезов.";
  const priceRows = data.prices.flatMap(route => ["direct", "oneStop", "twoPlus"].flatMap((key, index) => {
    const bucket = route[key];
    return bucket ? [[`${data.origin} → ${route.code}`, stops[index], n(bucket.count), amount(bucket.mean), amount(bucket.median)]] : [];
  }));
  const pairRows = data.pairs.flatMap(route => ["oneStop", "twoPlus"].flatMap((key, index) => {
    const group = route[key];
    return group ? [[`${data.origin} → ${route.code}`, stops[index + 1], n(group.pairs), percent(group.medianPct),
      share(group.cheaper / group.pairs)]] : [];
  }));
  const top = data.offerCounts.indexOf(Math.max(...data.offerCounts));
  const sumPairs = matched.reduce((sum, match) => sum + (match?.pairs ?? 0), 0);
  return kpis([["Уникальные предложения", n(offers)], ["Направления", n(data.prices.length)], ["Парные сравнения", n(sumPairs)]])
    + `<div class="stack"><div class="columns">${bars("Предложения по типу перелёта", "Предложения, шт.", "Тип перелёта",
      `N=${n(offers)} уникальных предложений. ${period(data.snapshots)}`,
      stops.map((label, index) => ({ label, value: data.offerCounts[index] })), n,
      `Больше всего предложений в категории «${stops[top]}» — ${n(data.offerCounts[top])}.`)}
      ${bars("Разница с ценой прямого перелёта", "Медианная разница, %", "Тип пересадки",
        `N=${n(sumPairs)} пар. ${routeText}`,
        matched.map((stat, index) => ({ label: stops[index + 1], value: stat?.medianPct, sample: stat ? "N=" + n(stat.pairs) : "" })),
        percent, compare ? compare + "." : "Недостаточно сопоставимых пар на одинаковые даты.")}</div>
      ${table("Средние и медианные цены по маршрутам", ["Маршрут", "Пересадки", "Предложения", "Средняя", "Медиана"],
        priceRows, priceRows.length ? "Для каждой категории показаны маршруты с минимум тремя уникальными предложениями."
          : "На отдельных маршрутах пока меньше трёх предложений в каждой категории.")}
      ${table("Сопоставление с прямым рейсом по маршрутам", ["Маршрут", "Пересадки", "Пар", "Разница по медиане", "Дешевле прямого"],
        pairRows, pairRows.length ? `В таблицу вошли маршруты с минимум ${n(data.minPairs)} пар нужного типа. ${routeText}`
          : "Ни один маршрут не прошёл заданный порог пар.")}</div>`;
}

function delays(data) {
  if (!data.flightCount) return notice(unavailable);
  const total = data.total.metrics;
  const range = `${date(data.from)} – ${date(data.to)}`;
  const byLabel = new Map(data.heatmap.map(group => [group.label, group]));
  const metric = (group, key, count) => group[count] >= data.minSamples ? group.metrics[key] : null;
  const hourGrid = weekdays.map((day, index) => ({ label: days[index], cells: Array.from({ length: 24 }, (_, hour) => {
    const group = byLabel.get(day + "|" + hour);
    return group ? { value: metric(group, "medianDepartureDelayMin", "departureN"), count: group.departureN } : null;
  }) }));
  const hourMax = hourGrid.flatMap(row => row.cells.map((cell, index) => ({ ...cell, day: row.label, hour: index })))
    .filter(cell => Number.isFinite(cell.value)).sort((a, b) => b.value - a.value)[0];
  const daysSeries = data.weekdays.map(row => ({ label: weekday(row.label), value: metric(row, "medianDepartureDelayMin", "departureN"),
    sample: "N=" + n(row.departureN) }));
  const dateSeries = data.daily.map(row => ({ label: date(row.label), value: metric(row, "medianDepartureDelayMin", "departureN"),
    sample: "N=" + n(row.departureN) }));
  const describePeak = (series, empty) => {
    const peak = series.filter(row => Number.isFinite(row.value)).sort((a, b) => b.value - a.value)[0];
    return peak ? `Наибольшая медиана — ${minutes(peak.value)} (${peak.label}, ${peak.sample}).` : empty;
  };
  const statTable = (title, category, groups) => {
    const entries = Object.entries(groups).sort((a, b) => b[1].count - a[1].count);
    return table(title, [category, "Всего", "Вылет, медиана", "N вылетов", "Прилёт, медиана", "N прилётов", "Отмены", "Доля отмен"],
      entries.map(([label, stats]) => [label, n(stats.count), minutes(stats.departureMedian), n(stats.departureN),
        minutes(stats.arrivalMedian), n(stats.arrivalN), n(stats.cancelled), share(stats.cancellationRate)]),
      entries.length ? `Больше всего рейсов в группе «${entries[0][0]}» — ${n(entries[0][1].count)}. Для метрик нужен минимум ${n(data.minSamples)} наблюдений.` : unavailable);
  };
  const sample = `${range}. N=${n(data.total.departureN)} измерений задержки вылета. Минимум в группе: ${n(data.minSamples)}.`;
  const insufficient = "Ни один период не набрал заданный минимум измерений.";
  return kpis([["Рейсы в выборке", n(data.flightCount)], ["Медиана задержки вылета", minutes(total.medianDepartureDelayMin)],
    ["Медиана задержки прилёта", minutes(total.medianArrivalDelayMin)], ["Доля отмен", share(total.cancellationRate)]])
    + `<div class="stack">${heatmap("Задержки вылета по дню недели и часу", sample, "Локальный час вылета", "День недели",
      Array.from({ length: 24 }, (_, hour) => String(hour)), hourGrid, "минуты", minutes,
      hourMax ? `Наибольшая медиана — ${minutes(hourMax.value)} (${hourMax.day}, ${hourMax.hour}:00, N=${n(hourMax.count)}).` : insufficient, true)}
      <div class="columns">${bars("Задержка вылета по дням недели", "Медиана, мин", "День недели", sample, daysSeries, minutes, describePeak(daysSeries, insufficient))}
      ${bars("Задержка вылета по датам", "Медиана, мин", "Локальная дата вылета", sample, dateSeries, minutes, describePeak(dateSeries, insufficient))}</div>
      ${statTable("Задержки и отмены по аэропортам", "Аэропорт", data.byAirport)}
      ${statTable("Задержки и отмены по авиакомпаниям", "Авиакомпания", data.byAirline)}
      ${statTable("Задержки и отмены по маршрутам", "Маршрут", data.byRoute)}</div>`;
}

function airlines(data) {
  if (!data.groups) return notice("По выбранным параметрам нет групп с предложениями от двух авиакомпаний.");
  const best = data.airlines[0];
  const bestShare = [...data.airlines].sort((a, b) => b.cheapestShare - a.cheapestShare)[0];
  const groupText = data.perSnapshot ? "Сравнение внутри одного среза." : "Сравнение минимумов всех срезов.";
  const coverage = `N=${n(data.groups)} групп «маршрут и дата вылета». ${groupText}`;
  const threshold = `Порог: ${n(data.minPairs)} сравнений на маршрут и ${n(data.minDestinations)} маршрутов на авиакомпанию.`;
  return kpis([["Сопоставимые группы", n(data.groups)], ["Авиакомпании в отчёте", n(data.airlines.length)], ["Ценовые срезы", n(data.snapshots.count)]])
    + `<div class="stack">${bars("Надбавка к самой дешёвой цене", "Медианная надбавка, %", "Авиакомпания", coverage + " " + threshold,
      data.airlines.map(row => ({ label: `${row.code} · ${row.name}`, value: row.medianPct, sample: "N=" + n(row.comparisons) })),
      percent, best ? `Наименьшая надбавка у ${best.name} (${best.code}) — ${percent(best.medianPct)}.` : "Порог выборки не прошла ни одна авиакомпания.")}
      ${table("Как часто авиакомпания предлагала минимум", ["Авиакомпания", "Маршруты", "Сравнения", "Надбавка", "Надбавка, ₽", "Доля минимумов"],
        data.airlines.map(row => [`${row.code} · ${row.name}`, n(row.destinations), n(row.comparisons), percent(row.medianPct),
          amount(row.medianRub), share(row.cheapestShare)]),
        bestShare ? `Выше всего доля самых дешёвых предложений у ${bestShare.name} (${share(bestShare.cheapestShare)} её сравнений).` : threshold)}
      ${table("Цены авиакомпаний по маршрутам", ["Маршрут", "Авиакомпания", "Сравнения", "Медианная надбавка"],
        data.routes.map(route => [`${data.origin} → ${route.destination}`, route.airline, n(route.price.comparisons), percent(route.price.medianPct)]),
        data.routes.length ? "Процент показывает надбавку к самому дешёвому предложению на ту же дату и маршрут." : threshold)}</div>`;
}

function field(label, name, value = "", type = "text", attributes = "", wide = false) {
  return `<label class="field ${wide ? "wide" : ""}"><span>${escaped(label)}</span>
    <input name="${name}" type="${type}" value="${escaped(value)}" ${attributes}></label>`;
}

function select(label, name, values, selected, placeholder) {
  const current = selected != null && selected !== "" ? String(selected) : "";
  return `<label class="field"><span>${escaped(label)}</span><select name="${name}">
    ${placeholder === null ? "" : `<option value="">${escaped(placeholder)}</option>`}
    ${values.map(([value, label]) => `<option value="${escaped(value)}" ${String(value) === current ? "selected" : ""}>${escaped(label)}</option>`).join("")}
    </select></label>`;
}

const options = values => values.map(value => [value, value]);
const check = (label, name, checked) => `<label class="check"><input type="checkbox" name="${name}" ${checked ? "checked" : ""}>${escaped(label)}</label>`;
const iataAttributes = 'pattern="[A-Za-z]{3}" minlength="3" maxlength="3" autocapitalize="characters" placeholder="SVO"';
const positiveAttributes = 'min="1" step="1"';

function resultFilters(id, current) {
  const { data, filters } = current;
  let fields;
  if (id === 3) {
    fields = field("Вылеты с", "from", filters.from ?? data?.from, "date")
      + field("Вылеты по", "to", filters.to ?? data?.to, "date")
      + field("Аэропорты вылета", "airports", filters.airports, "text",
        data?.airports?.length ? `placeholder="${escaped(data.airports.slice(0, 4).join(", "))}"` : 'placeholder="SVO, LED"', true)
      + field("Минимум наблюдений", "minSamples", filters.minSamples ?? 10, "number", positiveAttributes);
  } else if (id === 5) {
    fields = field("Аэропорт вылета", "origin", filters.origin, "text", iataAttributes)
      + field("Аэропорт назначения", "destination", filters.destination, "text", iataAttributes)
      + field("Локальная дата вылета", "date", filters.date, "date");
  } else {
    const available = [...new Set([...(data?.origins ?? []), ...(filters.origin ? [filters.origin] : [])])].sort();
    fields = available.length ? select("Пункт вылета", "origin", options(available),
      filters.origin ?? data?.origin, null) : field("Пункт вылета", "origin", filters.origin, "text", iataAttributes);
    if (id === 1) {
      fields += select("Направление для сезонности", "destination", options(data?.destinations ?? []),
        filters.destination, "Все направления");
    }
    fields += field("Месяц вылета", "month", filters.month, "month");
    if (id === 2 || id === 4) {
      fields += field("Минимум сравнений", "minPairs", filters.minPairs ?? 3, "number", positiveAttributes)
        + check("Объединять срезы", "allSnapshots", filters.allSnapshots !== "false");
    }
    if (id === 4) {
      fields += field("Минимум направлений", "minDestinations", filters.minDestinations ?? 3, "number", positiveAttributes)
        + check("Только прямые", "directOnly", filters.directOnly !== "false");
    }
  }
  return `<form class="filters" data-form="filters" aria-label="Фильтры результатов">${fields}
    <button type="submit" class="button" ${current.loading ? "disabled" : ""}>${id === 5 ? "Найти рейсы" : "Применить"}</button></form>`;
}

function flightList(data) {
  const coverage = kpis([["Будущие вылеты по фильтрам", n(data.total)], ["Профили с историей цены и задержки", n(data.profileCount)]]);
  if (!data.total) {
    return coverage + notice(data.profileCount
      ? "В сохранённых ценовых снимках нет будущих вылетов с подходящим профилем. Измените фильтры или обновите список вылетов."
      : "Общий каталог профилей пуст. Постройте его во вкладке «Обновить данные».");
  }
  const pageCount = Math.ceil(data.total / data.pageSize);
  const paging = pageCount > 1 ? `<div class="pagination"><button type="button" class="button secondary" data-page="${data.page - 1}" ${data.page <= 1 ? "disabled" : ""}>Предыдущая</button>
    <span class="hint">Страница ${n(data.page)} из ${n(pageCount)}</span>
    <button type="button" class="button secondary" data-page="${data.page + 1}" ${data.page >= pageCount ? "disabled" : ""}>Следующая</button></div>` : "";
  if (!data.flights.length) return coverage + notice("На этой странице нет рейсов.") + paging;
  const choices = data.flights.map((flight, index) => [index,
    `${flight.airlineIata} ${flight.flightNumber} · ${flight.originIata} → ${flight.destinationIata} · ${departure(flight.scheduledDeparture)}`]);
  return coverage + card("Доступные рейсы", "Выберите будущий вылет для общего прогноза цены и задержки.",
    `<form class="flight-picker" data-form="predict">${select("Рейс", "flight", choices, 0, null)}
      <button class="button" type="submit">Получить прогноз</button></form>${paging}
      <div id="flight-summary" class="flight-summary">${flightSummary(data.flights[0])}</div>
      <div id="prediction" class="prediction" aria-live="polite"></div>`);
}

function flightSummary(flight) {
  return `<strong>${escaped(flight.airlineIata)} ${escaped(flight.flightNumber)} · ${escaped(flight.originIata)} → ${escaped(flight.destinationIata)}</strong>
    <p>${escaped(departure(flight.scheduledDeparture))}</p>
    <p class="hint">История профиля: ${n(flight.priceHistoryFlights)} вылетов с ценами, ${n(flight.delayHistoryFlights)} с данными о задержках.</p>`;
}

function renderPrediction(result) {
  const price = result.price.currency === "RUB" ? amount(result.price.predictedPrice)
    : `${n(result.price.predictedPrice)} ${result.price.currency}`;
  const delay = n(result.delay.predictedDelayMin) + " мин";
  const context = result.delay.historicalContext;
  const history = context?.profileSampleSize > 0
    ? `Профиль задержки: N=${n(context.profileSampleSize)}, медиана ${minutes(context.profileMedianDelayMin)}.` : "";
  return kpis([["Прогноз цены", price], ["Прогноз задержки вылета", delay]])
    + `<p class="hint">${escaped(history)} Модель задержки ${escaped(result.delay.modelVersion)}.</p>`;
}

const reports = { 1: seasonality, 2: transfers, 3: delays, 4: airlines, 5: flightList };

function pipelineForm(id, current) {
  const data = current.data;
  const defaults = current.pipelineParams ?? {};
  const collect = defaults.collect ?? id !== 5;
  const origin = defaults.origin ?? current.filters.origin ?? data?.origin ?? "";
  let fields;
  if (id === 3) {
    fields = field("Дата начала", "from", defaults.from ?? data?.from, "date", "required")
      + field("Дата окончания", "to", defaults.to ?? data?.to, "date", "required")
      + field("Аэропорты через запятую", "airports", defaults.airports?.join(", ") ?? current.filters.airports, "text",
        'required placeholder="SVO, LED"', true)
      + `<div data-needs-collection>${field("Лимит новых окон", "maxRequests", defaults.maxRequests, "number",
        positiveAttributes + ' max="200" placeholder="20"')}</div>`;
  } else {
    const priceFields = field("Пункт вылета", "origin", origin, "text", iataAttributes + " required")
      + field("Коды назначения через запятую", "destinations", defaults.destinations?.join(", ") ?? current.filters.destination,
        "text", 'placeholder="AER, OVB"', true)
      + field("Месяц вылета" + (id === 1 ? " (по желанию)" : ""), "month", defaults.month ?? current.filters.month, "month")
      + `<div data-needs-collection>${field("Лимит запросов к API", "maxRequests", defaults.maxRequests, "number", positiveAttributes + ' max="200"')}</div>`;
    fields = id === 5
      ? `<div class="fields" data-needs-collection>${priceFields}</div><div class="fields">
        ${field("Минимум вылетов с задержками", "minDelayFlights", defaults.minDelayFlights ?? 3, "number", positiveAttributes + " required")}
        ${field("Минимум вылетов с ценами", "minPriceFlights", defaults.minPriceFlights ?? 3, "number", positiveAttributes + " required")}</div>`
      : `<div class="fields">${priceFields}</div>`;
  }
  const heading = id === 5 ? "Обновление общего каталога" : "Параметры обновления";
  const help = id === 3 ? "AeroDataBox делит дни на два 12-часовых окна. Сохранённые окна используются повторно."
    : id === 5 ? "Каталог строится по общей истории. Свежие вылеты можно загрузить из Aviasales, историю задержек — в задаче «Задержки»."
      : id === 1 ? "Загрузите календарь цен для выбранных направлений или обработайте уже сохранённый календарь."
        : "Укажите пункт вылета, направления и месяц. Лимит учитывает повторные запросы Aviasales.";
  return `<section class="card pipeline"><div class="card-heading"><h2>${heading}</h2><p>${help}</p></div>
    <form data-form="pipeline">
      ${id === 3 ? `<div class="fields">${fields}</div>` : fields}
      <div class="actions">${check(id === 5 ? "Загрузить свежие вылеты из Aviasales" : "Запросить новые данные у источника", "collect", collect)}
        <button type="submit" class="button" ${current.pending ? "disabled" : ""}>Запустить обновление</button></div>
    </form><div class="pipeline-status" aria-live="polite">${jobStatus(current)}</div></section>`;
}

function jobStatus(current) {
  if (current.jobError) return notice(current.jobError, "error");
  if (!current.job) return "";
  return notice(`${stages[current.job.status]} · ${current.job.message}`,
    current.job.status === "FAILED" ? "error" : "info");
}

function adjustPipeline(form) {
  const collect = form.elements.collect.checked;
  for (const section of form.querySelectorAll("[data-needs-collection]")) {
    section.hidden = !collect;
    section.querySelectorAll("input").forEach(input => { input.disabled = !collect; });
  }
  if (form.elements.destinations) form.elements.destinations.required = collect;
  if (form.elements.month) form.elements.month.required = collect && task !== 1;
}

function render() {
  const current = state.get(task);
  document.querySelector("#task-title").textContent = tasks.get(task);
  document.querySelectorAll(".tasks a").forEach(link => {
    if (link.hash === "#task-" + task) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  document.querySelectorAll(".modes button").forEach(button =>
    button.setAttribute("aria-pressed", String(button.dataset.mode === current.mode)));
  tables.clear();
  if (current.mode === "pipeline") {
    panel.innerHTML = pipelineForm(task, current);
    adjustPipeline(panel.querySelector("form"));
    return;
  }
  const completed = current.job?.status === "COMPLETED" ? notice(current.job.message, "info") : "";
  const content = current.loading ? notice("Загружаем подготовленные данные…") : current.error ? notice(current.error, "error")
    : current.data ? reports[task](current.data) : "";
  panel.innerHTML = resultFilters(task, current) + completed + `<div ${current.loading ? 'aria-busy="true"' : ""}>${content}</div>`;
}

async function load(id) {
  const current = state.get(id);
  const request = Symbol();
  current.request = request;
  current.loading = true;
  current.error = "";
  if (id === task) render();
  const query = new URLSearchParams(current.filters);
  const endpoint = id === 5 ? "flights" : "results";
  try {
    const data = await api(`/api/tasks/${id}/${endpoint}?${query}`);
    if (current.request === request) current.data = data;
  } catch (error) {
    if (current.request === request) current.error = error.message;
  } finally {
    if (current.request === request) {
      current.loading = false;
      if (id === task && current.mode === "results") render();
    }
  }
}

function navigate() {
  const id = Number(location.hash.match(/^#task-(\d)$/)?.[1]);
  if (tasks.has(id)) task = id;
  const current = state.get(task);
  if (current.mode === "results" && !current.loading && !current.data) load(task);
  else render();
}

function showJob(id) {
  if (task !== id || state.get(id).mode !== "pipeline") return;
  const current = state.get(id);
  panel.querySelector(".pipeline-status").innerHTML = jobStatus(current);
  panel.querySelector('button[type="submit"]').disabled = current.pending;
}

const splitCodes = value => String(value ?? "").split(/[\s,]+/).filter(Boolean).map(code => code.toUpperCase());

function storage(id, record) {
  const key = "avia-job-" + id;
  try {
    if (record === undefined) return JSON.parse(sessionStorage.getItem(key));
    if (record === null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, JSON.stringify(record));
  } catch {
    // Polling continues in this tab when session storage is unavailable.
  }
  return null;
}

async function startJob(id, form) {
  const current = state.get(id);
  const entries = new FormData(form);
  const parameters = { collect: form.elements.collect.checked };
  for (const [key, value] of entries) {
    if (!value || key === "collect") continue;
    if (key === "airports" || key === "destinations") parameters[key] = splitCodes(value);
    else if (["maxRequests", "minDelayFlights", "minPriceFlights"].includes(key)) parameters[key] = Number(value);
    else parameters[key] = key === "origin" ? value.toUpperCase() : value;
  }
  current.pipelineParams = parameters;
  current.pending = true;
  current.job = null;
  current.jobError = "";
  showJob(id);
  try {
    current.job = await post(`/api/tasks/${id}/pipeline`, parameters);
    storage(id, { jobId: current.job.jobId, parameters });
    showJob(id);
    poll(id, current.job.jobId);
  } catch (error) {
    current.jobError = error.message;
    current.pending = false;
    showJob(id);
  }
}

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function poll(id, jobId) {
  const current = state.get(id);
  let errors = 0;
  while (current.pending) {
    await pause(1100);
    try {
      current.job = await api(`/api/jobs/${jobId}`);
      errors = 0;
    } catch (error) {
      if (++errors < 3) continue;
      current.jobError = error.message;
      current.pending = false;
    }
    const completed = current.job?.status === "COMPLETED";
    if (completed || current.job?.status === "FAILED") current.pending = false;
    if (!current.pending) storage(id, null);
    if (completed) {
      current.mode = "results";
      const params = current.pipelineParams ?? {};
      if ([1, 2, 4].includes(id)) {
        if (params.origin !== current.filters.origin) delete current.filters.destination;
        current.filters.origin = params.origin;
        if (params.month) current.filters.month = params.month;
        else delete current.filters.month;
      }
      if (id === 3) {
        const airports = (params.airports ?? []).filter(code => code.length === 3);
        current.filters = { from: params.from, to: params.to };
        if (airports.length) current.filters.airports = airports.join(",");
      }
      if (id === 5) delete current.filters.page;
      load(id);
    } else {
      showJob(id);
    }
  }
}

async function predict(form, current) {
  const { airlineIata, flightNumber, originIata, destinationIata, scheduledDeparture } =
    current.data.flights[Number(form.elements.flight.value)];
  const body = { airlineIata, flightNumber, originIata, destinationIata, scheduledDeparture, predictionTimeUtc: new Date().toISOString() };
  const output = document.querySelector("#prediction");
  const button = form.querySelector("button");
  button.disabled = true;
  form.elements.flight.disabled = true;
  output.innerHTML = notice("Рассчитываем цену и задержку. Первый прогноз может занять больше времени.");
  try {
    output.innerHTML = renderPrediction(await post("/api/tasks/5/predict", body));
  } catch (error) {
    output.innerHTML = notice(error.message, "error");
  } finally {
    button.disabled = false;
    form.elements.flight.disabled = false;
  }
}

panel.addEventListener("submit", event => {
  event.preventDefault();
  const form = event.target;
  const current = state.get(task);
  if (form.dataset.form === "pipeline") {
    startJob(task, form);
  } else if (form.dataset.form === "predict") {
    predict(form, current);
  } else if (form.dataset.form === "filters") {
    current.filters = Object.fromEntries([...new FormData(form)].filter(([, value]) => value !== ""));
    for (const checkbox of form.querySelectorAll('input[type="checkbox"]')) {
      current.filters[checkbox.name] = String(checkbox.checked);
    }
    if (current.filters.airports) current.filters.airports = splitCodes(current.filters.airports).join(",");
    load(task);
  }
});

panel.addEventListener("change", event => {
  const input = event.target;
  if (input.name === "collect") adjustPipeline(input.form);
  if (input.form?.dataset.form === "filters" && task === 1 && input.name === "origin") {
    input.form.elements.destination.value = "";
  }
  if (input.name === "flight") {
    const flight = state.get(5).data.flights[Number(input.value)];
    document.querySelector("#flight-summary").innerHTML = flightSummary(flight);
    document.querySelector("#prediction").innerHTML = "";
  }
});

panel.addEventListener("click", event => {
  const button = event.target.closest("button");
  if (button?.dataset.table) {
    const id = button.dataset.table;
    const table = tables.get(id);
    const next = table.shown + 25;
    document.getElementById(id).insertAdjacentHTML("beforeend", tableRows(table.rows.slice(table.shown, next)));
    table.shown = next;
    if (next >= table.rows.length) {
      button.textContent = "Показаны все строки";
      button.disabled = true;
    }
  }
  if (button?.dataset.page) {
    state.get(5).filters.page = button.dataset.page;
    load(5);
  }
});

document.querySelectorAll(".modes button").forEach(button => button.addEventListener("click", () => {
  const current = state.get(task);
  if (button.dataset.mode === current.mode) return;
  current.mode = button.dataset.mode;
  if (current.mode === "results" && !current.loading && !current.data) load(task);
  else render();
}));

for (const id of tasks.keys()) {
  const saved = storage(id);
  if (saved?.jobId) {
    const current = state.get(id);
    current.mode = "pipeline";
    current.pending = true;
    current.pipelineParams = saved.parameters;
    poll(id, saved.jobId);
  }
}
window.addEventListener("hashchange", navigate);
navigate();
