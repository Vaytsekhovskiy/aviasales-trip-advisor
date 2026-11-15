# Repository integration

Аудит выполнен по объединённой ветке `master` 2026-11-15. До интеграции remote по умолчанию использовал `main`; ветки `master` и `ask3` не существовали. Локальные `main`, `task3`, `task5` не содержали commits, отсутствующих на remote. После `git fetch --all --prune` дополнительно была создана локальная tracking-ветка `feature/add-data-collector` для одноимённой remote branch.

Safety point: annotated tag `pre-task-merge-2026-11-15` на commit `7fbe33a` (`main`, `task 2 done`). Новая integration branch `master` создана от этой точки.

| Ветка | Состояние после fetch | Роль | Результат интеграции |
|---|---|---|---|
| `main` | `7fbe33a`, совпадала с `origin/main` | исходная база, ранние задачи 1–2 | база `master` |
| `feature/add-data-collector` | `fed7f9a`, совпадала с remote | Mongo collector, графики задач 2 и 4, ONNX-прогноз цены | merge commit `4546bfc` |
| `task5` | `bf7aae0`, совпадала с remote | общая линия delay pipeline и Python/CatBoost task5 до разделения веток | merge commit `02cfad6` |
| `task3` | `2ea0aae`, совпадала с remote | описательный анализ задержек; последний commit удаляет task5 ML из этой ветки | merge commit `b2672f9` |
| `ask3` | отсутствует локально и на remote | опечатка/неактуальное имя | merge не нужен |

При merge `task5` были конфликты в `.gitignore`, `README.md` и `src/main/java/avia/Main.java`. Разрешение сохранило обе стороны: правила для Java ONNX и Python/data/secrets, документацию обоих направлений, delay CLI и существующие price/transfer/Mongo modes. Merge `task3` технически прошёл без конфликтов, но ожидаемо применил commit удаления task5 ML. В `master` это удаление отменено commit `b19a200`, поэтому:

- `task3` остаётся исторической веткой без Python ML;
- `task5` остаётся веткой с CatBoost/delay-v2;
- `master` содержит и task3 descriptive analytics, и обе части совместной task5.

## Build and test results

- `mvn test`: **BUILD SUCCESS**, скомпилированы 82 main и 14 test source files. Важное ограничение: Surefire нашёл 0 JUnit tests, потому что Java checks оформлены как standalone `main`.
- Все 14 standalone Java checks запущены напрямую: passed, включая AeroDataBox, delay analysis/visualization, enrichment, Mongo transformations и локальные HTTP mocks.
- `python -m pytest`: **28 passed**. Было только предупреждение о невозможности создать pytest cache в уже существующем локальном cache path.
- JavaScript smoke tests для delay dashboard и offer dashboard: passed.
- ML-модель не переобучалась; реальные API и Mongo write pipelines не запускались.

# Five tasks overview

| Task | Purpose | Data API | Raw collection | Prepared collection | Analysis/Model | Current visualization |
|---|---|---|---|---|---|---|
| 1 | Сезонность цен и выбор дешёвого направления | Travelpayouts `v1/prices/calendar`; persistent pipeline также использует `aviasales/v3/prices_for_dates` | `raw_calendar`, `raw_prices_for_dates` или локальный CSV | `offers_clean` | `SeasonalityAggregator`, `CheapestDestinationAggregator` | HTML heatmap «месяц × день недели» |
| 2 | Сравнение прямых рейсов и рейсов с пересадками | Travelpayouts `v1/prices/cheap`; persistent pipeline — `prices_for_dates` | `raw_prices_for_dates` | `offers_clean` | `TransferStats`, `PairedTransferStats` | HTML bar charts, route comparison, scatter, CSV |
| 3 | Исторический описательный анализ задержек без ML | AeroDataBox FIDS; для offer enrichment — Travelpayouts `prices_for_dates` | `raw_aerodatabox`, `raw_prices_for_dates`, локальные `data/raw` | `flights`, `flight_statistics`, `enriched_flight_offers`, локальные `data/flights` | `DelayAnalyzer`, `FlightHistoryAggregator`, `DelayVisualizationAggregator` | delay HTML dashboards, heatmaps, bars, trend, scatter, offer table/scatter |
| 4 | Сравнение ценовой политики авиакомпаний | Travelpayouts `aviasales/v3/prices_for_dates` | `raw_prices_for_dates` | `offers_clean` | `AirlineStats` | HTML tabs, two bar charts, detailed table, route × airline matrix, CSV |
| 5 | Совместное ML-прогнозирование: цена и задержка | Нового API-вызова при inference нет; используются подготовленные price/model artifacts и Mongo `flights` | исходные price datasets для ONNX не включены; для delay — `raw_aerodatabox` | ONNX artifact; `flights`; CatBoost `.cbm` + metadata | Java ONNX `PricePredictionService`; Python CatBoost `DelayPredictor`, delay-v2 training/evaluation | CLI JSON/number, PNG evaluation plots, JSON/TXT/CSV reports |

# Detailed task descriptions

## TASK 1 — Сезонность цен и дешёвые направления

### Business / research purpose

Определить, в какие месяцы и дни недели перелёт по выбранному маршруту дешевле, либо какое направление из выбранного города имеет минимальную среднюю цену для каждой календарной группы.

### Data source and inputs

- Direct mode: Travelpayouts `GET /v1/prices/calendar` через `AviasalesClient`.
- Route inputs: `origin`, опциональный `destination`, token, currency.
- `departure_date` намеренно пустой: API возвращает календарь ближайшего года. Произвольный date range этот entry point не принимает.
- Persistent/shared price mode: `Collector` и `AviasalesPricesBulkCollectorCli` работают с `prices_for_dates`; bulk CLI принимает месяц и список аэропортов.

### Entry points, storage and preprocessing

- Direct collection/analysis: `avia.Main ORIGIN DESTINATION TOKEN [CURRENCY]` или `avia.Main ORIGIN TOKEN [CURRENCY]`.
- Route generation: `avia.RouteBuilder TOKEN ...`, результат `routes_auto.txt`.
- Shared persistent collector: `avia.Collector TOKEN [routes.txt] [currency]`.
- Raw storage: direct mode пишет `raw_prices_ORIGIN_DESTINATION.csv` / `raw_prices_ORIGIN_any.csv`; Mongo pipeline использует `avia.raw_calendar` и `avia.raw_prices_for_dates`.
- Preprocessing: `Cleaner` нормализует price, dates, transfers, airline, trip type, `days_before`, удаляет невалидные значения и дубли внутри snapshot.
- Prepared storage: `avia.offers_clean`; reader — `MongoSource`.

### Analysis, visualization and output

`SeasonalityAggregator` группирует цены в матрицу 12 месяцев × 7 дней недели и считает average, median и N. `CheapestDestinationAggregator` выбирает направление с минимальной средней ценой в каждой ячейке.

- Heatmap «Сезонность цен»: X = день недели, Y = месяц, цвет и значение = средняя цена, tooltip = N.
- Heatmap «Куда дешевле лететь»: X = день недели, Y = месяц, ячейка = IATA/город и средняя цена.
- Output: standalone HTML и raw CSV; Mongo mode создаёт такие же HTML из prepared data.

### Dependencies and pipeline

Java 17, `org.json`, Java HTTP client; Mongo driver для prepared mode; Travelpayouts token.

`Travelpayouts calendar/prices_for_dates → CSV или raw_* Mongo → Cleaner → offers_clean → Seasonality/CheapestDestination aggregator → HTML heatmap`

### UI readiness

- Prepared-results mode: да, если `offers_clean` содержит источник `calendar`. Но текущий `Collector.urls()` активно собирает только `raw_prices_for_dates`, а `raw_calendar` закомментирован; это реальный разрыв pipeline.
- Full-pipeline mode: `RouteBuilder` → `Collector`/bulk collector → `Main --clean` → `Main --mongo ORIGIN [DESTINATION]`.
- Safe rerun: calendar direct mode только читает API и перезаписывает локальные output names; старый `Collector` делает append и может создавать повторные snapshots. `Cleaner` поддерживает incremental mode; `--rebuild` удаляет и пересоздаёт только `offers_clean`.

## TASK 2 — Прямые рейсы против пересадочных

### Business / research purpose

Проверить, насколько билеты с одной или несколькими пересадками дороже/дешевле прямых на сопоставимых направлениях и датах.

### Data source and inputs

- Direct mode: Travelpayouts `GET /v1/prices/cheap`, параметры `origin`, `destination=-`, currency, token.
- Prepared mode: `prices_for_dates` snapshots из `avia.offers_clean`.
- Inputs: origin; token/currency для direct mode; `--min-pairs N` и `--all-snapshots` для Mongo analysis.
- Отдельного date range в direct mode нет. В Mongo mode диапазон определяется накопленными departure dates/snapshots.

### Entry points and storage

- Direct: `avia.Main --transfers ORIGIN TOKEN [CURRENCY]`.
- Prepared: `avia.Main --mongo --transfers ORIGIN [--min-pairs N] [--all-snapshots]`.
- Raw/prepared: `raw_prices_for_dates → Cleaner → offers_clean`.
- Direct output также сохраняет CSV без Mongo.

### Analysis

`TransferStats` считает count, mean и median для категорий 0, 1 и 2+ пересадок. `PairedTransferStats` сравнивает минимальные цены на одинаковые destination + departure date, по умолчанию в одном snapshot; считает абсолютную и процентную разницу относительно прямого рейса.

### Visualization and output

- «Число билетов по категориям»: X = direct / 1 stop / 2+ stops, Y = количество билетов.
- «На сколько пересадки дороже прямого»: X = категория пересадки, Y = процент к direct.
- «По направлениям»: X = destination, Y = процентная разница; top routes по числу пар.
- Scatter: X = цена прямого рейса, Y = цена рейса с пересадкой; диагональ показывает равенство цен.
- Output: `transfers_comparison_*.html`, scatter/summary HTML и агрегированный CSV.

### Dependencies and pipeline

`Travelpayouts cheap/prices_for_dates → raw_prices_for_dates/CSV → Cleaner → offers_clean → paired transfer aggregation → HTML + CSV`

Java 17, JSON, MongoDB в prepared mode. Credentials: token позиционным аргументом в старом CLI; `AVIASALES_TOKEN` + `MONGO_URI` в bulk pipeline.

### UI readiness

- Prepared-results mode: да; `MongoSource.loadTransferData` читает `offers_clean` без API.
- Full-pipeline mode: `Collector` или `AviasalesPricesBulkCollectorCli --month YYYY-MM` → `Main --clean` → `Main --mongo --transfers ...`.
- Bulk collector безопаснее старого collector: snapshot identity и upsert; `--max-requests` ограничивает попытки. Повторный старый `Collector` создаёт новый snapshot и не является строгим idempotent load.

## TASK 3 — Исторический анализ задержек без ML

### Business / research purpose

Описать фактические задержки вылета/прилёта, отмены и компенсацию задержки по аэропортам, авиакомпаниям, маршрутам, датам, дням недели и часам. Эта задача не обучает и не вызывает ML.

### Data source and inputs

- AeroDataBox RapidAPI FIDS через `AeroDataBoxClient`.
- Inputs: airport IATA/ICAO, date или `from/to`, hourly window (1–12 часов), airport list, max batches/API attempts, minimum sample thresholds.
- Defaults для batch plan: SVO, LED, AER, OVB, SVX.
- Credentials: `AERODATABOX_API_KEY`; для Mongo paths также `MONGO_URI`.
- Для объединения цены и истории используется Travelpayouts `prices_for_dates`, credential `AVIASALES_TOKEN`.

### Collection entry points and rerun safety

- Local CSV pipeline: `Main --delays`, `--delays-import`, `--delays-reprocess`, `--delays-plan`, `--delays-collect`, `--delays-report`.
- Mongo bulk: `AeroDataBoxBulkCollectorCli --from DATE --to DATE [--airports ...] [--max-batches N] [--dry-run]`.
- Import already saved JSON: `AeroDataBoxRawMongoImporter [data/raw]`.
- Price snapshots for enrichment: `AviasalesPricesBulkCollectorCli --month YYYY-MM [--airports ...] [--max-requests N] [--dry-run]`.
- Rerun safety: local pipeline skips existing CSV and reuses raw JSON; Mongo AeroDataBox uses unique `batch_key` and resumes; no parallel collectors should run. `--delays-reprocess` is offline. API collector stops on errors rather than silently replacing good data.

### Raw and prepared storage

- Local raw: `data/raw/YYYY-MM-DD/*.json`; reference cache `data/reference/airports`; collection log `data/collection.jsonl`.
- Local prepared: `data/flights/YYYY-MM-DD/*.csv`.
- Mongo raw: `avia.raw_aerodatabox`; price raw: `avia.raw_prices_for_dates`.
- `MongoFlightNormalizerCli`: raw batches → normalized/deduplicated `avia.flights`.
- `MongoFlightStatisticsCli`: `flights` → `avia.flight_statistics` keyed by regular flight service.
- `MongoFlightEnrichmentCli`: `raw_prices_for_dates` + `flight_statistics` → `avia.enriched_flight_offers`.

### Preprocessing and analysis

`FlightPreprocessor` normalizes airports, airline/flight number, scheduled/revised/actual times, statuses and stable `flightId`. `MongoRawFlightNormalizer` globally deduplicates observations. `FlightObservationOrder` consistently chooses the newest/most complete observation.

`DelayAnalyzer`, `FlightHistoryAggregator` and `DelayVisualizationAggregator` calculate median/mean departure and arrival delay, P(delay > 15/30/60), cancellation share, delay recovery/compensation, sample sizes and group breakdowns. Missing values have separate denominators; cancellation is excluded from delay metrics.

### Existing visualizations

- Local `DelayHtmlRenderer`: per-airport heatmaps, columns = local departure hour 0–23, rows = weekday, color = median departure delay, label = N; tables by departure airport, airline and route.
- Mongo delay dashboard: KPI cards and tables; weekday bars (X weekday, Y median departure delay); airport bars; airline horizontal bars; date trend (X date, Y median delay); heatmap weekday × hour; scatter X departure delay / Y arrival delay with `y=x` recovery line.
- Offer dashboard: filterable table of price snapshots and historical stats; scatter X price / Y historical median departure delay. It explicitly describes history, not a per-flight forecast.
- Output paths: `reports/delays_FROM_TO_nN.html`, `output/delay-dashboard.html`, `output/offer-dashboard.html`.

### Dependencies and pipeline

`AeroDataBox API → raw JSON/raw_aerodatabox → FlightPreprocessor/MongoRawFlightNormalizer → flights → DelayAnalyzer/FlightHistoryAggregator → flight_statistics → delay dashboard`

Optional cross-source pipeline:

`Travelpayouts prices_for_dates + flight_statistics → enriched_flight_offers → offer dashboard`

### UI readiness

- Prepared-results mode: да. `MongoDelayDashboardCli` читает только `flights`; `MongoOfferDashboardCli` читает только `enriched_flight_offers`; local report читает CSV.
- Full-pipeline mode: collect/import → `MongoFlightNormalizerCli` → `MongoFlightStatisticsCli` → optional `MongoFlightEnrichmentCli` → dashboard CLIs.
- Для web orchestration нужны job status/progress и защита от параллельного запуска; текущие collectors синхронные CLI.

## TASK 4 — Сравнение стоимости авиакомпаний

### Business / research purpose

Сравнить ценовую политику авиакомпаний на одинаковых origin/destination/departure date, не позволяя популярным маршрутам непропорционально влиять на итог.

### Data source, inputs and storage

- Travelpayouts `aviasales/v3/prices_for_dates`.
- Inputs collection: month, airport list, max requests; analysis: origins, `--min-pairs`, `--min-destinations`, `--all-snapshots`, `--any-transfers`.
- Credentials: `AVIASALES_TOKEN`, `MONGO_URI`.
- Raw: `avia.raw_prices_for_dates`; preprocessing `Cleaner`; prepared: `avia.offers_clean`.
- Entry point: `Main --mongo --airlines [ORIGIN ...] ...`; collection through `Collector` or `AviasalesPricesBulkCollectorCli`.

### Analysis

`MongoSource.loadAirlineGroups` формирует группы «route + departure date + optional snapshot», оставляет минимальную цену каждой авиакомпании и только конкурентные группы. `AirlineStats` считает median/p25/p75 premium к самой дешёвой компании, median overpay RUB, долю случаев cheapest/most expensive, число направлений/сравнений и разрез по горизонту бронирования.

### Visualization and output

- Bar «На сколько авиакомпания дороже самой дешёвой»: X = airline, Y = median premium %.
- Bar «Как часто авиакомпания самая дешёвая»: X = airline, Y = share of comparisons %.
- Detailed table: destinations, comparisons, median/IQR premium, RUB overpay, cheapest/expensive share, early/near departure metrics.
- Matrix: rows = routes, columns = airlines, cell = median premium %, color from green to orange.
- Tabs per origin and pooled «all origins» section.
- Output: `airlines_comparison_*_mongo.html` and `raw_airline_prices_*_mongo.csv`.

### Pipeline and UI readiness

`Travelpayouts prices_for_dates → raw_prices_for_dates → Cleaner → offers_clean → MongoSource/AirlineStats → HTML tabs + CSV`

- Prepared-results mode: да, полностью offline после подготовки `offers_clean`.
- Full-pipeline mode: `AviasalesPricesBulkCollectorCli --month ...` → `Main --clean` → `Main --mongo --airlines ...`.
- Date range представлен месяцем collection и фактическими departure dates, но arbitrary `from/to` collector отсутствует.

## TASK 5 — ML-прогнозирование цены и задержки

Task 5 совместно реализована двумя разработчиками и имеет два равноценных подпотока. Они не дублируют друг друга: один прогнозирует цену, второй — задержку.

### TASK 5A — Прогноз цены, Java + ONNX

#### Purpose, inputs and entry point

Прогноз стоимости конкретного будущего перелёта. `Main --predict-price AIRLINE FLIGHT_NUMBER ORIGIN DESTINATION DEPARTURE_WITH_OFFSET PREDICTION_TIME_UTC` создаёт `FlightPredictionRequest` и вызывает `PricePredictionService`.

Model features: departure month/day/hour/weekday, days before departure, airline, flight number, origin and destination. Inference API/credentials не нужны.

#### Storage, preprocessing and output

- Expected artifact: classpath resource `/models/price_model.onnx` (`src/main/resources/models/price_model.onnx`).
- Java преобразует timestamps, вычисляет `days_before`, кодирует scalar/string tensors и получает output `variable` через ONNX Runtime.
- Output: одно числовое значение прогнозной цены в CLI; отдельной visualization нет.
- В текущем merged checkout ONNX artifact отсутствует и исключён `.gitignore`; код компилируется, но inference завершится `ONNX model not found`, пока artifact не будет предоставлен локально/через deployment.
- Код/metadata training pipeline для price ONNX в repository отсутствуют, поэтому воспроизводимость обучения по текущему master проверить нельзя.

#### Pipeline and UI readiness

`prepared training data (outside current repository) → trained price_model.onnx → Java PricePredictionService → numeric CLI result`

- Prepared-results mode: да при наличии ONNX artifact; Mongo и API не требуются.
- Full-pipeline mode: в текущем repository отсутствует training/export entry point; web UI сможет вызвать только inference после доставки модели.

### TASK 5B — Прогноз задержки, Python + CatBoost delay-v2

#### Purpose, inputs and entry points

Прогноз `max(0, revisedDepartureUtc - scheduledDepartureUtc)` в минутах для будущего рейса.

- Inference: `python -m ml.predict_delay --json-file request.json` или explicit `--airline`, `--flight-number`, `--origin`, `--destination`, `--scheduled-departure`, optional `--model-version v1|v2`.
- Training/evaluation: `python -m ml.train_delay_v2` (не запускался при integration).
- Batch inference: `python -m ml.batch_predict_delay`.
- Credentials: только `MONGO_URI`; API key не требуется.

#### Data, preprocessing and model

- Source/prepared collection: `avia.flights`; raw upstream — `avia.raw_aerodatabox`.
- `data_loader`, `preprocessing` and `features` validate/deduplicate and build schedule features.
- `historical_features` строит leakage-safe strictly-past features на уровнях exact profile → service → route → airline → global.
- Model: `CatBoostRegressor`, MAE loss; v2 uses chronological train/validation/test split and hierarchical median baseline.
- Artifacts: `models/delay_model_v1.cbm`, `models/delay_model_v2.cbm` and metadata JSON. Эти artifacts присутствуют в repository.

#### Analysis, visualization and output

- Inference stdout JSON: normalized flight, exact predicted delay, model version, history support level and sample/median/rate context. Prediction is not persisted.
- Evaluation artifacts: `actual_vs_predicted.png` (X actual, Y predicted delay), `absolute_error_distribution.png` (X absolute error, Y frequency/count), `feature_importance.png` (X importance, Y feature), JSON/TXT metrics, target diagnostics and external prediction CSV/JSON/TXT.
- `delay_v2_evaluation.json` stores baseline/model MAE metrics, heavy-tail slices and temporal split audit.

#### Pipeline and UI readiness

`AeroDataBox → raw_aerodatabox → MongoFlightNormalizerCli → flights → leakage-safe historical features → CatBoost train/evaluate → delay_model_v2.cbm → predict_delay JSON`

- Prepared-results mode: да. Для inference нужны existing `flights`, `.cbm`, metadata и `MONGO_URI`; training не требуется.
- Full-pipeline mode: AeroDataBox collection/import → normalize to `flights` → optional `train_delay_v2` only when deliberate retraining is requested → inference/refresh evaluation artifacts.
- Для web UI целесообразно отделить read-only inference от expensive training job. Текущий Python CLI не предоставляет REST layer.

# Existing pipelines

| Pipeline | Entry points | Writes | Idempotency / safety |
|---|---|---|---|
| Shared Aviasales snapshots | `Collector`, `AviasalesPricesBulkCollectorCli` | `raw_prices_for_dates`, fallback JSONL | bulk uses snapshot IDs/upsert and bounded requests; old collector appends snapshots |
| Shared price cleanup | `Main --clean [--rebuild]` | `offers_clean` | incremental by snapshot; `--rebuild` drops only prepared collection |
| Local delay collection | `Main --delays*` / `DatasetCli` | `data/raw`, `data/flights`, report HTML | skips CSV, reuses JSON; `reprocess` is offline |
| Mongo delay collection | `AeroDataBoxBulkCollectorCli`, raw importer | `raw_aerodatabox`, local backup JSON | unique batch key; resumable; dry-run available |
| Mongo delay preparation | `MongoFlightNormalizerCli`, `MongoFlightStatisticsCli` | `flights`, `flight_statistics` | bulk upsert; refuses partial normalization result |
| Cross-source enrichment | `MongoFlightEnrichmentCli` | `enriched_flight_offers` | source collections read-only; upsert by source offer ID |
| Prepared dashboards | `MongoDelayDashboardCli`, `MongoOfferDashboardCli`, `Main --mongo ...` | local HTML/CSV only | Mongo read-only |
| Price inference | `Main --predict-price` | stdout only | read-only, requires externally supplied ONNX artifact |
| Delay inference | `python -m ml.predict_delay` | stdout only | read-only Mongo/history; does not persist prediction |
| Delay training | `python -m ml.train_delay_v2` | model + metadata + evaluation outputs | intentionally not a normal refresh action; overwrites versioned v2 artifacts |

# Existing visualizations

| Task | Visualization | Type | Source | X / columns | Y / rows / value |
|---|---|---|---|---|---|
| 1 | Price seasonality | HTML heatmap | calendar/offers_clean | weekday | month; color/value = average price |
| 1 | Cheapest destination | HTML heatmap | calendar/offers_clean | weekday | month; cell = destination + average price |
| 2 | Ticket category counts | SVG bars in HTML | offers_clean | 0/1/2+ stops | count |
| 2 | Transfer premium | SVG bars | paired prices | stop category or destination | % relative to direct |
| 2 | Direct vs transfer price | SVG scatter | paired prices | direct price | transfer price |
| 3 | Delay heatmap | HTML table heatmap | flights/CSV | local departure hour | weekday; color = median delay, label = N |
| 3 | Delay groups | SVG bars + tables | flights | weekday/airport/airline | median departure delay and rates |
| 3 | Delay by date | SVG points/trend | flights | date | median departure delay |
| 3 | Delay recovery | canvas scatter | flights | departure delay | arrival delay |
| 3 | Price vs history | SVG scatter + table | enriched_flight_offers | price | historical median departure delay |
| 4 | Airline premium | SVG bars | offers_clean | airline | median premium % |
| 4 | Cheapest frequency | SVG bars | offers_clean | airline | cheapest share % |
| 4 | Route/airline premium | HTML matrix | offers_clean | airline | route; cell = median premium % |
| 5B | Actual vs predicted | PNG scatter | evaluation split | actual delay | predicted delay |
| 5B | Absolute error distribution | PNG histogram | evaluation split | absolute error | frequency |
| 5B | Feature importance | PNG horizontal bars | CatBoost model | importance | feature |

# Candidate parameters for "Load data"

| Task | Candidate parameters | Credentials | Post-load actions |
|---|---|---|---|
| 1 | origin, optional destination, currency; for bulk: month, airports/routes, max requests | `AVIASALES_TOKEN`, `MONGO_URI` | `Cleaner`; then seasonality/cheapest report |
| 2 | origin, currency; bulk month/airports; analysis min pairs and snapshot policy | `AVIASALES_TOKEN`, `MONGO_URI` | `Cleaner`; paired transfer analysis |
| 3 | from, to, airports, max batches/FIDS requests, hourly window, min samples | `AERODATABOX_API_KEY`, `MONGO_URI` | normalize → statistics → optional enrichment → dashboards |
| 4 | month, origins/airports, max requests, direct-only, min pairs/destinations, snapshot policy | `AVIASALES_TOKEN`, `MONGO_URI` | `Cleaner`; airline analysis |
| 5A | no load action at inference; airline, flight, route, departure timestamp, prediction timestamp | none; ONNX artifact required | ONNX inference |
| 5B | history from/to and airports upstream; inference flight identity + scheduled departure + model version | `AERODATABOX_API_KEY` upstream, `MONGO_URI` | normalize; normally inference only, explicit retrain when requested |

# Shared infrastructure

- Java 17/Maven monolith with CLI dispatch in `avia.Main`; Mongo sync driver; JSON; ONNX Runtime.
- Mongo database `avia`; shared price path `raw_prices_for_dates → offers_clean`; shared delay path `raw_aerodatabox → flights → flight_statistics`.
- Python package `ml` with pandas, NumPy, scikit-learn, CatBoost, matplotlib, PyMongo and pytest.
- Shared cross-source key: airline + flight number + origin + destination. Exact profile adds local weekday/hour.
- Standalone HTML renderers embed data and JavaScript, so prepared reports work offline without a web server.
- Environment variable names only: `MONGO_URI`, `AVIASALES_TOKEN`, `AERODATABOX_API_KEY`. Values are not stored in this report.

# Risks / technical debt

1. No REST/backend layer or job orchestration; all pipelines are blocking CLIs.
2. README combines documentation from parallel histories; delay documentation appears before the general setup section, so its structure still needs a later editorial pass.
3. `pom.xml` uses `source/target 17` instead of `--release 17`; Maven warns when compiling with the IntelliJ runtime JDK.
4. Java tests are executable smoke classes, not JUnit; `mvn test` compiles them but reports zero executed tests.
5. Task 1 Mongo mode expects `source=calendar`, while the current `Collector` has `raw_calendar` collection disabled/commented.
6. Old CLI paths accept API tokens as positional arguments, which can expose them in shell history/process listings; bulk CLIs correctly use environment variables.
7. The price ONNX inference artifact and its training/export pipeline are absent from the repository; runtime price prediction is not self-contained.
8. Delay-v2 contains fixed temporal cutoffs/dates for the current educational dataset; retraining on new periods requires code/config changes.
9. Large CatBoost binaries and generated evaluation artifacts are committed. This preserves reproducibility of inference but increases repository size and mixes code with outputs.
10. Mongo schema is implicit in Java/Python code; there are no migrations or schema version checks.
11. Several analytics and renderers are tightly coupled to local HTML file generation; a future web UI should reuse service/aggregator layers, not parse generated HTML.
12. Collectors have different idempotency guarantees; old `Collector` may append duplicate snapshots, while new bulk collectors use explicit identities.
13. Existing `.idea` project files are tracked from earlier history. No new `.venv`, local `data`, caches, `.env`, credentials or local Mongo files were added by this integration.
