# Нормализация AeroDataBox из MongoDB

Путь: `avia.raw_aerodatabox` → существующий `FlightPreprocessor` → глобальная
дедупликация по `flightId` → `avia.flights`. CLI не читает CSV/локальные raw-файлы
и не выполняет запросы к API авиаданных.

## Запуск

В IntelliJ запустите `main` класса `avia.MongoFlightNormalizerCli`, затем добавьте
`MONGO_URI` в Environment variables именно этой конфигурации. Аргументы не нужны.

Или после `mvn package`:

```powershell
java -cp target/avia-seasonality.jar avia.MongoFlightNormalizerCli
```

Все raw batches читаются заново при каждом запуске. Upsert по `flightId` заменяет
документ результатом глобального выбора из текущей raw-коллекции. Старые документы,
больше не представленные в raw, автоматически не удаляются. Запускайте один normalizer
за раз, после окончания raw-импорта. Если raw batch повреждён, остальные batches
проверяются, но запись не начинается: неполная выборка не должна заменять хорошую запись.
Запись выполняется через `bulkWrite(ordered=false)` пачками до 5000 upsert-операций:
для 85514 рейсов это 18 bulk-вызовов на уровне приложения. `Updated` включает все matched documents,
даже если значения не изменились. Ошибки отдельных записей и пачек учитываются в `Errors`;
остальные записи и пачки обрабатываются. При ошибке сети или write concern
результат части записей может быть неизвестен: они показаны в `Unconfirmed documents`.
При ошибках процесс заканчивается с кодом 1; повторный запуск безопасен по ID.

## Выбор наблюдения и поля

`FlightObservationOrder.COMPARATOR` используется совместно с `DelayAnalyzer` и
`FlightHistoryAggregator`: последнее `collectedAtUtc`, полнота задержек и scheduled
departure local, предпочтение departure, затем `FlightRecord.toString()`.
Поля наблюдений не смешиваются. Все исходные batch keys сохраняются в `sourceBatchKeys`.
Пустые/отсутствующие ID считаются в summary и не записываются.

Raw importer не знает исторического времени сбора. Поэтому `ingested_at` игнорируется,
а `collectedAtUtc` остаётся null. Если raw содержит отдельное достоверное поле
`collected_at_utc` (BSON Date или ISO Instant), используется оно. Время из имени batch
также не подставляется как время сбора. Summary показывает число batches без времени.

Сохраняются все 52 компонента `FlightRecord` под существующими именами, включая
`departureHour`, `arrivalHour`, `collectedAtUtc`. Instant записывается как BSON Date
(миллисекундная точность Mongo), LocalDateTime — ISO-строкой без назначения часового пояса,
DayOfWeek — строкой. Null сохраняется. `flightServiceKey` — четыре компонента без даты;
при неполном ключе null. Аналитические значения не пересчитываются writer-ом.

`MongoFlightStorage` — отдельный writer: batch-based `exists` из `FlightStorage`
не соответствует глобальной дедупликации и не реализуется фиктивно.

Индексы: `flightId` unique с partial filter `{flightId: {$type: 'string'}}`,
обычные `flightServiceKey`, `originIata`, `destinationIata`, `airlineIata`,
`scheduledDepartureUtc`. Writer дополнительно отвергает пустые/пробельные ID.

## Проверки

После `mvn package` standalone-тесты запускаются отдельно от Surefire:

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.MongoFlightNormalizerTest
java -cp "target/test-classes;target/avia-seasonality.jar" avia.MongoFlightNormalizerTest data/raw
java -cp "target/test-classes;target/avia-seasonality.jar" avia.FlightHistoryAggregatorTest
java -cp "target/test-classes;target/avia-seasonality.jar" avia.DelayAnalysisTest
```

Второй запуск использует локальные raw только как offline test fixtures, без MongoDB.
Проверенный недельный набор: 70 batches, 12666 observations, 10981 unique stable flights,
1176 duplicates, 509 missing IDs. DP6525: 7 отдельных ID; задержки вылета
64, 41, 13, 58, 14, 224, 439. Это не отчёт об исполнении на Atlas.

В VS Code MongoDB Playground (запускайте запросы отдельно):

```javascript
use('avia');
db.getCollection('flights').countDocuments({});
```

```javascript
use('avia');
db.getCollection('flights').find(
  {flightServiceKey: 'DP|6525|SVO|OVB'},
  {_id: 0, flightId: 1, scheduledDepartureUtc: 1, departureDelayMin: 1,
   arrivalDelayMin: 1, sourceBatchKeys: 1}
).sort({scheduledDepartureUtc: 1});
```

```javascript
use('avia');
db.getCollection('flights').aggregate([
  {$group: {_id: '$flightId', count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}}
]);
```

Aviasales collections и `raw_aerodatabox` не изменяются. Коллекции статистики
и enriched offers не создаются.
