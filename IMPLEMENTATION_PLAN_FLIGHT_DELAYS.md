# Aviasales Trip Advisor — план реализации модуля анализа задержек рейсов

## 1. Цель

Добавить в существующий проект `aviasales-trip-advisor` новый модуль для сбора и анализа задержек авиарейсов.

Первый этап:

**AeroDataBox API → предобработка → CSV → аналитика → HTML-визуализация**

Ключевое архитектурное требование: основная логика не должна зависеть от способа хранения. Сейчас используем CSV, позже `CsvFlightStorage` можно заменить на `S3FlightStorage`/Object Storage.

Основная исследовательская задача:

> Анализ факторов задержек авиарейсов: влияние аэропорта/города вылета и прилёта, авиакомпании, направления, дня недели и времени суток.

Дополнительная метрика:

> Насколько рейс компенсирует задержку вылета к моменту прилёта.

---

## 2. AeroDataBox

Источник operational-данных — **AeroDataBox через RapidAPI**.

Бесплатный Basic:
- 400 API units / месяц;
- FIDS — Tier 2;
- 1 FIDS-запрос = 2 units;
- максимальное окно = 12 часов.

Один аэропорт за сутки:

```text
00:00–12:00 → 2 units
12:00–24:00 → 2 units
```

Итого: **4 units / аэропорт / сутки**.

---

## 3. Основной endpoint

Использовать FIDS по абсолютному локальному времени:

```text
GET /flights/airports/{codeType}/{code}/{fromLocal}/{toLocal}
```

Параметры:

```text
codeType=iata
direction=Both
withLeg=true
withCancelled=true
withCodeshared=false
withCargo=false
withPrivate=false
withLocation=false
```

Назначение:
- `Both` — одновременно arrivals + departures;
- `withLeg=true` — получить, где возможно, противоположный leg;
- `withCancelled=true` — сохранять отмены;
- `withCodeshared=false` — уменьшить дубли;
- `withCargo=false` — исключить грузовые рейсы.

---

## 4. Health check

Перед массовым сбором проверить live coverage:

```text
GET /health/services/airports/{icao}/feeds
```

Интересует:

```text
liveFlightUpdatesFeed
```

Предварительные аэропорты:

```text
SVO / UUEE — Москва
LED / ULLI — Санкт-Петербург
AER / URSS — Сочи
OVB / UNNT — Новосибирск
SVX / USSS — Екатеринбург
```

Критерий отбора: крупный аэропорт отдельного города + достаточное live coverage.

---

## 5. Что подтвердил тестовый JSON по SVO

Реальный ответ показал:

```text
259 рейсов
136 departures
123 arrivals
```

Качество:
- flight number — 100%;
- airline — 100%;
- status — 100%;
- scheduled time в наблюдаемом аэропорту — 100%;
- revised time в наблюдаемом аэропорту — 100%;
- runway time — почти 100%;
- противоположный аэропорт — почти 100%;
- достаточно данных для обеих задержек примерно у 82% строк.

Также:
- `codeshareStatus = IsOperator`;
- cargo отсутствует;
- отменённые рейсы есть;
- `withLeg=true` реально работает;
- `revisedTime` на втором конце маршрута иногда отсутствует.

Следовательно, second-leg поля должны быть nullable.

---

## 6. Время и задержки

Сохранять:

```text
scheduledTime
revisedTime
runwayTime
```

Основные метрики:

```text
departure_delay_min =
revised_departure_utc - scheduled_departure_utc

arrival_delay_min =
revised_arrival_utc - scheduled_arrival_utc
```

Дополнительные:

```text
takeoff_delta_min =
runway_departure_utc - scheduled_departure_utc

landing_delta_min =
runway_arrival_utc - scheduled_arrival_utc
```

Компенсация задержки:

```text
delay_recovery_min =
departure_delay_min - arrival_delay_min
```

Положительное значение означает, что часть задержки была компенсирована по пути.

Для расчётов использовать UTC, для анализа часа/дня недели — local time аэропорта.

---

## 7. Особенности FIDS

### Временное окно

Рейс может попасть в окно запроса не только по scheduled time. Поэтому файл `SVO_00-12.csv` означает **окно запроса**, а не гарантирует scheduled departure всех строк в 00–12.

### Origin / destination

Для `departures` origin airport может не повторяться в записи — подставлять `sourceAirport`.

Для `arrivals` аналогично destination = `sourceAirport`.

---

## 8. FlightRecord

Нормализованная модель должна содержать минимум:

```text
schema_version
flight_id
source_airport
direction

flight_number

airline_iata
airline_icao
airline_name

origin_iata
origin_icao
origin_name
origin_country
origin_timezone
origin_lat
origin_lon

destination_iata
destination_icao
destination_name
destination_country
destination_timezone
destination_lat
destination_lon

status
is_cancelled

scheduled_departure_utc
scheduled_departure_local
revised_departure_utc
revised_departure_local
runway_departure_utc
runway_departure_local

scheduled_arrival_utc
scheduled_arrival_local
revised_arrival_utc
revised_arrival_local
runway_arrival_utc
runway_arrival_local

departure_delay_min
arrival_delay_min
delay_recovery_min
takeoff_delta_min
landing_delta_min

departure_weekday
departure_hour
arrival_weekday
arrival_hour

aircraft_model
aircraft_reg

departure_terminal
arrival_terminal
departure_gate
arrival_gate

collected_at_utc
```

Поля, которых нет в конкретной записи, оставлять `null`.

---

## 9. Flight ID

Стабильный идентификатор:

```text
airline_iata
+ flight_number
+ origin_iata
+ destination_iata
+ scheduled_departure_utc
```

Пример:

```text
FV|6699|SVO|LED|2026-10-01T22:00Z
```

Он нужен для дедупликации между разными аэропортами и повторными загрузками.

---

## 10. Архитектура MVP

Пока НЕ использовать Spark/Hadoop/S3/Kafka/PostgreSQL.

```text
AeroDataBox API
      ↓
AeroDataBoxClient
      ↓
DTO / JSON
      ↓
FlightPreprocessor
      ↓
FlightRecord
      ↓
FlightStorage
      ↓
CsvFlightStorage
      ↓
CSV
      ↓
DelayAnalyzer
      ↓
DelayHtmlRenderer
```

---

## 11. Storage abstraction

```java
public interface FlightStorage {

    void save(
        List<FlightRecord> flights,
        FlightBatch batch
    );

    boolean exists(FlightBatch batch);
}
```

Сейчас:

```text
CsvFlightStorage
```

Позже:

```text
S3FlightStorage
```

`FlightCollector` и аналитика не должны знать, где физически лежат данные.

---

## 12. FlightBatch

```java
public record FlightBatch(
    String airport,
    LocalDate date,
    LocalTime from,
    LocalTime to
) {}
```

Пример:

```text
SVO / 2026-10-02 / 00:00 / 12:00
```

---

## 13. CSV layout

Не использовать один огромный `flights.csv`.

```text
data/
└── flights/
    ├── 2026-10-02/
    │   ├── SVO_00-12.csv
    │   ├── SVO_12-24.csv
    │   ├── LED_00-12.csv
    │   └── ...
    └── 2026-10-03/
        └── ...
```

Такая структура потом почти напрямую переносится в Object Storage.

Перед API-запросом проверять:

```java
if (storage.exists(batch)) {
    return;
}
```

Это предотвращает повторный расход units.

---

## 14. Рекомендуемая структура пакетов

```text
client/
    AviasalesClient.java
    AeroDataBoxClient.java

dto/
    AeroDataBoxFlightDto.java

model/
    FlightRecord.java
    FlightBatch.java

service/
    FlightPreprocessor.java
    FlightCollector.java
    DelayAnalyzer.java

storage/
    FlightStorage.java
    CsvFlightStorage.java

renderer/
    DelayHtmlRenderer.java
```

API DTO и доменную модель не смешивать:

```text
AeroDataBox JSON
        ↓
DTO
        ↓
FlightPreprocessor
        ↓
FlightRecord
```

---

## 15. FlightCollector

Пример логики:

```java
public class FlightCollector {

    private final AeroDataBoxClient client;
    private final FlightPreprocessor preprocessor;
    private final FlightStorage storage;

    public void collect(FlightBatch batch) {

        if (storage.exists(batch)) {
            return;
        }

        var response = client.fetchFlights(batch);
        var records = preprocessor.process(response, batch);

        storage.save(records, batch);
    }
}
```

---

## 16. FlightPreprocessor

Должен делать:

```text
JSON parsing
↓
normalization departures/arrivals
↓
sourceAirport substitution
↓
UTC/local datetime parsing
↓
origin/destination normalization
↓
flight_id
↓
status / is_cancelled
↓
departure_delay_min
↓
arrival_delay_min
↓
delay_recovery_min
↓
takeoff_delta_min
↓
landing_delta_min
↓
weekday / hour
↓
validation
```

Не должен считать агрегаты по аэропортам/авиакомпаниям — это `DelayAnalyzer`.

---

## 17. Отмены

Не удалять.

Хранить:

```text
status = Canceled
is_cancelled = true
```

Из обычной статистики задержек исключать, но позже считать:

```text
cancellation_rate
```

---

## 18. Первая аналитика

### По аэропорту

```text
count
median departure delay
median arrival delay
P(delay > 15)
P(delay > 30)
P(delay > 60)
cancellation rate
```

### По авиакомпании

Та же статистика.

### По времени

```text
weekday
departure_hour
```

### По маршруту

```text
origin → destination
```

Использовать минимальный размер группы, например `N >= 30`.

### Компенсация

```text
departure_delay - arrival_delay
```

---

## 19. Первые визуализации

1. Heatmap:

```text
weekday × departure_hour
→ median departure delay
```

2. Bar chart:

```text
airport → median departure delay
```

3. Scatter:

```text
X = departure_delay
Y = arrival_delay
```

Добавить линию `Y = X`.

Точки ниже линии — рейс компенсировал часть задержки.

---

## 20. Aviasales enrichment

Aviasales не должен блокировать MVP.

Основной датасет:

```text
AeroDataBox → flights.csv
```

Дополнительно позже:

```text
Aviasales → prices.csv
```

Планируем использовать:

```text
GET /aviasales/v3/prices_for_dates
```

Предпочтительно:

```text
direct=true
one_way=true
currency=rub
```

Потенциальные поля:

```text
origin_airport
destination_airport
airline
flight_number
departure_at
price
duration
```

Join:

```text
airline
+ flight_number
+ origin
+ destination
+ departure datetime/date
```

Результат:

```text
enriched_flights.csv
```

Join будет неполным, потому что Aviasales Data API не представляет все существующие рейсы. Основной анализ задержек должен работать без него.

---

## 21. План реализации

### MVP 0 — без расхода API units

Использовать уже сохранённый реальный JSON.

Реализовать:

```text
FlightRecord
FlightBatch
FlightStorage
CsvFlightStorage
FlightPreprocessor
```

Добиться:

```text
saved JSON → normalized CSV
```

### MVP 1 — API

Добавить:

```text
AeroDataBoxClient
FlightCollector
```

Режим запуска, например:

```text
--delays SVO 2026-10-02
```

Два окна:
- 00–12
- 12–24

### MVP 2 — несколько аэропортов

Сначала 1–2 дня:

```text
SVO
LED
AER
OVB
SVX
```

Проверить:
- пропуски;
- дубли;
- coverage;
- распределения;
- отмены.

### MVP 3 — первый срез

Собрать:

```text
5 airports × 7–14 days
```

### MVP 4 — аналитика

Добавить:

```text
DelayAnalyzer
DelayHtmlRenderer
```

### MVP 5 — Aviasales

Добавить ценовое enrichment только после рабочего delay pipeline.

---

## 22. Что пока сознательно не делать

Не начинать с:

```text
Hadoop
Spark
S3
Yandex Object Storage
VK Cloud
PostgreSQL
Kafka
Airflow
```

Сначала получить рабочий датасет, убедиться в качестве данных и сделать пробную визуализацию.

После этого можно эволюционировать:

```text
CsvFlightStorage
→ S3FlightStorage
→ Parquet / Bronze-Silver-Gold / Spark
```

без изменения основной предметной логики.

---

## 23. Критерий готовности первого этапа

MVP готов, если:

1. тестовый AeroDataBox JSON разбирается;
2. departures и arrivals приводятся к единой `FlightRecord`;
3. origin/destination восстанавливаются корректно;
4. задержки рассчитываются в UTC;
5. nullable second-leg данные не ломают pipeline;
6. cancelled рейсы сохраняются;
7. формируется стабильный `flight_id`;
8. данные пишутся в отдельные CSV batches;
9. повторный запуск не повторяет существующий batch;
10. `DelayAnalyzer` умеет прочитать CSV и посчитать базовую статистику.

---

## 24. Первый task для IDE-агента

Начать с:

```text
1. Изучить текущую структуру aviasales-trip-advisor.
2. Не ломать существующие режимы проекта.
3. Добавить FlightRecord.
4. Добавить FlightBatch.
5. Добавить FlightStorage.
6. Реализовать CsvFlightStorage.
7. Реализовать FlightPreprocessor для сохранённого AeroDataBox JSON.
8. Добавить небольшой CLI/test entry point для JSON → CSV.
9. Проверить результат на тестовом JSON.
10. Только после этого реализовать AeroDataBoxClient.
```

Главный принцип:

```text
Source
  ↓
Normalizer
  ↓
Domain Model
  ↓
Storage
  ↓
Analyzer
```

Это позволит позже независимо менять источник данных и слой хранения.
