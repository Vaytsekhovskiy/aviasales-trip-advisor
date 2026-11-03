# Историческая статистика из MongoDB

`avia.flights` → `MongoFlightReader.readAll()` → существующий
`FlightHistoryAggregator.aggregate()` → `avia.flight_statistics`.
Reader восстанавливает существующий FlightRecord, игнорируя Mongo `_id` и технические поля.
UTC BSON Date преобразуется в Instant, ISO local time — в LocalDateTime,
weekday — в DayOfWeek, числовые BSON значения — в соответствующие Java-типы.
Отсутствующие nullable поля остаются null. Некорректный документ прерывает чтение
до начала записи статистики: расчёт по неполной выборке не публикуется.

FlightReader с `read(FlightBatch)` не реализован: после глобальной дедупликации
нужна вся коллекция, а не отдельный batch. Reader и writer реализуют AutoCloseable.

## Запуск

В IntelliJ: запустите main класса `avia.MongoFlightStatisticsCli`, добавьте
`MONGO_URI` в Environment variables новой конфигурации. Аргументы не нужны.
Переменная из конфигурации normalizer автоматически не переносится.

Или после `mvn package`:

```powershell
java -cp target/avia-seasonality.jar avia.MongoFlightStatisticsCli
```

Запускайте после завершения нормализации, когда `flights` не изменяется.
CLI выводит число прочитанных рейсов, рассчитанных ключей, вставок, обновлений,
ошибок, итоговое число документов и документ DP6525 после записи.
Один сервис с ошибкой записи не останавливает запись остальных; итоговый exit code — 1.
Секреты и сообщения исключений драйвера не печатаются.

Индексы: unique `flightServiceKey`, обычные `airlineIata`, `originIata`, `destinationIata`.
CLI использует `bulkWrite(ordered=false)` пачками до 5000 `ReplaceOneModel(upsert=true)`:
обновляются все метрики и BSON Date `computedAt`. Для 2887 сервисов это один bulk-вызов.
`Updated` учитывает matched documents, включая записи без изменений.
`Write errors` — число диагностик (ошибок отдельных записей или целой пачки).
При сетевой ошибке или неподтверждённом write concern число документов с неизвестным
результатом отображается как `Unconfirmed documents`, без фиктивного увеличения успехов.
Остальные пачки обрабатываются; ошибки приводят к exit code 1. Можно безопасно повторить запуск.
Числа не округляются, неопределённые метрики остаются null.
Повторный запуск при неизменном входе: Inserted=0, Updated=число рассчитанных ключей.
Статистика для сервисов, полностью удалённых из источника, автоматически не удаляется.

## Локальные тесты

После `mvn package`:

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.MongoFlightStatisticsTest
java -cp "target/test-classes;target/avia-seasonality.jar" avia.MongoFlightStatisticsTest data/raw
```

Это offline-проверки BSON round-trip, nullable полей, ошибок типов, контрольных метрик,
новых дней и повторного формирования документов. Они не подключаются к Atlas и
не являются проверкой фактического Mongo upsert/unique index.
Локальные raw fixtures дали 10981 рейс и 2887 service keys; после двух проходов
in-memory mapping осталось 2887 документов. Production CLI читает только Mongo `flights`.

## Проверка в VS Code MongoDB Playground

Запускайте запросы отдельно:

```javascript
use('avia');
db.getCollection('flight_statistics').countDocuments({});
```

```javascript
use('avia');
db.getCollection('flight_statistics').findOne({flightServiceKey: 'DP|6525|SVO|OVB'});
```

После повторного запуска CLI проверьте неизменность количества и отсутствие дублей:

```javascript
use('avia');
db.getCollection('flight_statistics').aggregate([
  {$group: {_id: '$flightServiceKey', count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}}
]);
```

На недельном наборе DP6525: sampleSize=7, medianDepartureDelayMin=58,
meanDepartureDelayMin=121.85714285714286, medianArrivalDelayMin=70,
meanArrivalDelayMin=130.42857142857142, departureDelay15Rate=departureDelay30Rate=0.7142857142857143,
departureDelay60Rate=0.42857142857142855, cancellationRate=0, medianDelayRecoveryMin=-3.

Pipeline пишет только в `flight_statistics`. `flights`, raw-коллекции и Aviasales
collections не изменяются; enriched_flight_offers не создаётся.
