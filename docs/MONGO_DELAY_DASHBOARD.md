# Dashboard задержек из MongoDB

Production pipeline: `MongoFlightReader` (только `avia.flights`) →
`DelayVisualizationAggregator` → `DelayDashboardData` → `DelayDashboardHtmlRenderer`.
Никаких записей Mongo, Aviasales, flight_statistics или повторной дедупликации.

В IntelliJ запустите `avia.MongoDelayDashboardCli.main()`, затем добавьте MONGO_URI
в Environment variables этой конфигурации. Working directory — корень проекта.
Аргументы не нужны. После завершения откройте `output/delay-dashboard.html` браузером.

После `mvn package`:

```powershell
java -cp target/avia-seasonality.jar avia.MongoDelayDashboardCli
# Необязательно: путь, min N для heatmap, min N для авиакомпаний:
java -cp target/avia-seasonality.jar avia.MongoDelayDashboardCli output/delay-dashboard.html 10 50
```

CLI выводит число рейсов, доступных задержек, аэропортов, авиакомпаний, период и
агрегаты по дням, аэропортам и top airlines. Период определяется scheduledDepartureLocal,
а не датами запросов FIDS. Вход читается полностью; ошибка чтения прерывает построение.
Рекомендуется запускать после успешного завершения normalizer, без одновременного изменения flights.

## Методика

Математика переиспользует `FlightHistoryAggregator.summarizeObservations` — выделенный
публичный метод существующего агрегатора. Он не группирует и не дедуплицирует записи.
Медиана, среднее, строгие пороги >15/>30/>60, доля отмен и медиана компенсации
рассчитываются единственной существующей реализацией. Историческая агрегация также
продолжает использовать этот метод. Формулы не изменены.

* N на delay-графиках — доступные неотменённые departure delays.
* Arrival N — собственный знаменатель; scatter N — пары с обеими задержками.
* sampleSize / «Рейсов» — все физические рейсы группы, включая отмены и null delays.
* Отмены не участвуют в delay-метриках. Null не превращается в ноль.
* Дополнительного ограничения статуса Departed/Arrived нет: правила соответствуют
  FlightHistoryAggregator, а не дополнительным status-фильтрам старого DelayAnalyzer.
* Компенсация = departureDelayMin − arrivalDelayMin. Положительная — задержка
  уменьшилась; отрицательная — увеличилась. Доли вычисляются среди всех пар,
  включая пары с отрицательными задержками. Это изменение задержки, не доказательство
  физического сокращения времени полёта.
* День, час и дата берутся из scheduledDepartureLocal. Без него рейс остаётся в KPI
  и категориях, но не во временных группах. Число исключений показано.
* Аэропорты — originIata, не sourceAirport: arrivals исходных FIDS batches добавляют
  множество аэропортов с частичным покрытием. Unknown показан отдельной группой,
  но не включён в количество известных аэропортов/авиакомпаний.

## Визуализации

1. Weekday bar: Monday–Sunday; tooltip с медианой, средним, вероятностями и N.
2. Heatmap 7×24: MIN_HEATMAP_SAMPLE=10, малые N нейтральны. Порог настраивается CLI.
3. Airport bar: все присутствующие аэропорты, горизонтальная прокрутка длинного
   графика; подробная таблица с N, вероятностями и долей отмен.
4. Airline horizontal bar: MIN_AIRLINE_SAMPLE=50 по доступным departure delays,
   top 15 по убыванию медианы. Unknown не ранжируется.
5. Scatter canvas: все пары встроены в HTML, y=x, tooltip, P1–P99 / полный диапазон.
   Число скрытых диапазоном точек явно указано, агрегаты не урезаются.
6. Daily line: календарный порядок, отсутствующие даты — null и разрыв линии.

Пустые данные отображаются как отсутствие наблюдений, а не нулевая задержка.
Все HTML/CSS/JS и данные встроены; нет CDN или backend. SVG/canvas без frontend framework.
Client-side фильтры аэропорта/авиакомпании в первой версии не реализованы.
HTML содержит полный массив пар задержек, но не Mongo URI, credentials или flight IDs.

## Тесты и локальный preview

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.DelayVisualizationTest
java -cp "target/test-classes;target/avia-seasonality.jar" avia.FlightHistoryAggregatorTest
java -cp "target/test-classes;target/avia-seasonality.jar" avia.DelayAnalysisTest
```

Опциональный offline preview использует локальные raw только как test fixtures:

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.DelayVisualizationTest data/raw
node src/test/js/delay-dashboard-smoke.cjs output/delay-dashboard-preview.html
```

Preview сохраняется отдельно в `output/delay-dashboard-preview.html` и помечен
«OFFLINE PREVIEW». Он НЕ заменяет отчёт из Mongo: в локальных raw JSON нет внешнего
collected_at, поэтому выбор duplicate observation может отличаться от Mongo.
Числа preview нельзя выдавать за прочитанные из Atlas. Production CLI не имеет CSV/raw fallback.

Standalone-тесты проверяют порядок дней, медианы/средние, N, пороги, группировку по часу,
дате и аэропорту, top airlines, null/отмены, recovery, пустые группы, отсутствие повторной
дедупликации и экранирование HTML. JS smoke проверяет построение графиков на заполненных
и пустых данных и переключение полного scatter-диапазона без npm-зависимостей.
