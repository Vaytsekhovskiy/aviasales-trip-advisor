# Spring Boot backend architecture

## Назначение рефакторинга

Spring Boot 3 на Java 17 предоставляет один REST API и веб-интерфейс для пяти задач. REST использует существующие агрегаторы, Mongo-схемы и ML-модели. Старые CLI продолжают строить самостоятельные HTML-отчёты.

Maven root временно оставлен в корне репозитория. Перенос всего проекта в `backend/` затронул бы множество рабочих относительных путей (`data/`, `reports/`, resources и standalone checks) и создавал бы риск, не связанный с package separation. Физическое разделение Java-кода выполнено полностью; перенос Maven root можно сделать отдельным контролируемым этапом.

## Структура репозитория

```text
.
├── pom.xml
├── src/main/java/avia
│   ├── AviaApplication.java
│   ├── Main.java
│   ├── MongoMain.java
│   ├── common/
│   ├── task1/
│   ├── task2/
│   ├── task3/
│   ├── task4/
│   ├── task5/{price,delay}/
│   └── web/
├── src/main/resources/application.yml
├── ml/
├── models/
├── data/
└── docs/
```

## Поток зависимостей

```mermaid
flowchart TD
    APP[AviaApplication] --> WEB[REST API и веб-интерфейс]
    WEB --> REG[TaskRegistry]
    APP --> SERVICES[Task application services]
    SERVICES --> ANALYTICS[Existing aggregators and models]
    SERVICES --> COMMON[common/shared infrastructure]
    COMMON --> MONGO[(MongoDB avia)]
    COMMON --> AVIA[Aviasales / Travelpayouts]
    COMMON --> AERO[AeroDataBox]
    ANALYTICS --> HTML[Existing HTML renderers]

    T5[Task 5] --> PRICE[Java price ONNX inference]
    T5 --> DELAY[Внутренний HTTP-сервис CatBoost]
```

REST возвращает JSON из task services. Веб-интерфейс строит графики и таблицы из этих данных, а CLI передаёт результаты тем же самостоятельным HTML-рендерерам.

## Package boundaries

| Task | Package | Main services | Shared dependencies |
|---|---|---|---|
| 1 — сезонность и дешёвые направления | `avia.task1` | `Task1Service`, `SeasonalityAggregator`, `CheapestDestinationAggregator` | `common.aviasales`, `common.price.mongo` |
| 2 — прямые рейсы и пересадки | `avia.task2` | `Task2Service`, `TransferStats`, `PairedTransferStats`, `TransferComparisonAggregator` | `common.aviasales`, `common.price` |
| 3 — описательный анализ задержек | `avia.task3` | `Task3Service`, `DelayAnalyzer`, `FlightHistoryAggregator`, `DelayVisualizationAggregator` | `common.aerodatabox`, `common.flight`, `common.mongo` |
| 4 — цены авиакомпаний | `avia.task4` | `Task4Service`, `AirlineStats` | `common.price`, `common.price.mongo` |
| 5 — ML-прогнозирование | `avia.task5.model`, `avia.task5.price`, `avia.task5.delay` | `FlightPredictionRequest`, `Task5PricePredictionService`, `PricePredictionService`, `Task5DelayProfileService` | `common.flight`; Python `ml/` и `models/` |

Task 3 не импортирует классы из `task5` и содержит только descriptive analytics. `FlightRecord`, `FlightHistoricalStats`, `FlightServiceIdentity`, порядок наблюдений и Mongo readers являются shared infrastructure, потому что используются и Task 3, и Task 5 delay.

`avia.task5.model.FlightPredictionRequest` — единый DTO `POST /api/tasks/5/predict`. Spring Boot проверяет, что
конкретный будущий вылет входит в общий каталог, проставляет текущее время и передаёт одинаковые поля в ONNX
и во внутренний HTTP-адаптер CatBoost. CatBoost также сверяет `_id` профиля в `predictable_flight_profiles`.

## Shared/common

- `avia.common.config` — typed Spring properties и bean configuration.
- `avia.common.mongo` — общий bulk-upsert helper.
- `avia.common.aviasales` — Travelpayouts client, collection services и raw storage.
- `avia.common.aerodatabox` — AeroDataBox client, DTO, collection и raw storage.
- `avia.common.flight` — нормализация, чтение/запись и shared flight domain.
- `avia.common.price` — общие справочники, cleaning и чтение `offers_clean`.

Пайплайны используют существующие collectors и предобработку. `PipelineJobs` выполняет одно обновление за раз, чтобы операции с общими коллекциями не пересекались. Чтение подготовленных данных не запускает сбор.

## Spring configuration

Entry point: `avia.AviaApplication` с `@SpringBootApplication` и `@ConfigurationPropertiesScan`.

`application.yml` использует только placeholders:

| Property | Environment variable | Default |
|---|---|---|
| Mongo URI | `MONGO_URI` | `mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=3000` |
| Mongo database | `MONGO_DATABASE` | `avia` |
| Aviasales token | `AVIASALES_TOKEN` | empty |
| AeroDataBox key | `AERODATABOX_API_KEY` | empty |
| ONNX file | `PRICE_MODEL_PATH` | `src/main/resources/models/price_model.onnx` |
| Delay service | `DELAY_SERVICE_URL` | `http://127.0.0.1:8090` |

Mongo synchronous driver сохранён. `MongoClient` зарегистрирован как lazy Spring bean, поэтому запуск backend и unit tests не открывают соединение без реального потребителя. External API clients создаются условно только при наличии соответствующего credential. Реальные API requests при старте не выполняются.

## REST и дистрибутив

- `GET /api/health` → `{"status":"UP"}`.
- `GET /api/tasks` → metadata ровно пяти задач из `TaskRegistry`.

`/api/tasks/{id}/results` обслуживает задачи 1–4. Task 5 использует `/flights` и `/predict` внутри `/api/tasks/5`.
`POST /api/tasks/{id}/pipeline` запускает фоновое обновление, а `GET /api/jobs/{jobId}` возвращает прогресс.
Frontend поставляется в том же JAR в `src/main/resources/static`. Compose запускает Java, CatBoost и MongoDB, при первом запуске загружая готовый архив. Публичный порт на локальной машине есть только у Spring Boot.

## Запуск и проверка

Из корня репозитория:

```powershell
mvn clean test
mvn spring-boot:run
```

После packaging:

```powershell
mvn clean package
java -jar target/avia-backend.jar
```

## CLI compatibility

`avia.Main` и `avia.MongoMain` сохранены как facades. Имена внутренних классов изменились, но пользовательские flags и аналитическая семантика не менялись. Из repackaged ZIP-layout archive старый dispatcher запускается так:

```powershell
java -Dloader.main=avia.Main -jar target/avia-backend.jar <старые аргументы>
java -Dloader.main=avia.MongoMain -jar target/avia-backend.jar <старые Mongo-аргументы>
```

Отдельные CLI перемещены в task/common packages; их replacement class names:

| Old class | New class |
|---|---|
| `avia.DatasetCli` | `avia.task3.cli.DatasetCli` |
| `avia.MongoDelayDashboardCli` | `avia.task3.cli.MongoDelayDashboardCli` |
| `avia.MongoFlightStatisticsCli` | `avia.task3.cli.MongoFlightStatisticsCli` |
| `avia.MongoPredictableFlightProfilesCli` | `avia.task5.delay.cli.MongoPredictableFlightProfilesCli` |
| `avia.AviasalesPricesBulkCollectorCli` | `avia.common.aviasales.cli.AviasalesPricesBulkCollectorCli` |
| `avia.AeroDataBoxBulkCollectorCli` | `avia.common.aerodatabox.cli.AeroDataBoxBulkCollectorCli` |
