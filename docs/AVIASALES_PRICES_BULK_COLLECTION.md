# Сбор Aviasales prices_for_dates и аудит совпадений

CLI: `avia.AviasalesPricesBulkCollectorCli`. В IntelliJ создайте конфигурацию из main
и задайте Environment variables `MONGO_URI` и **`AVIASALES_TOKEN`**.
Предыдущий Collector принимал токен аргументом; env-имени в проекте не было.
Новый CLI принимает токен только из AVIASALES_TOKEN, не из аргументов или файлов.

После `mvn package`:

```powershell
java -cp target/avia-seasonality.jar avia.AviasalesPricesBulkCollectorCli --month 2026-11 --dry-run
java -cp target/avia-seasonality.jar avia.AviasalesPricesBulkCollectorCli --month 2026-11 --max-requests 2
java -cp target/avia-seasonality.jar avia.AviasalesPricesBulkCollectorCli --month 2026-11
```

Опциональный список: `--airports SVO,LED,AER,OVB,SVX` (это default).
Повторные аэропорты, self routes и некорректные коды не допускаются.
При 5 аэропортах получается 20 направлений, порядок стабилен: каждый origin по порядку
списка, затем все отличающиеся destinations по тому же списку.

Dry-run не требует env, не создаёт MongoClient, не вызывает API и не пишет индексы.
Проверенный результат для ноября 2026:

```text
Month: 2026-11
Airports: SVO,LED,AER,OVB,SVX
Routes: 20
API requests planned: 20
```

## HTTP

Используется новый метод существующего AviasalesClient, прежние методы календаря
и Collector не переписаны. Endpoint `/aviasales/v3/prices_for_dates`:
`origin`, `destination`, `departure_at=YYYY-MM`, `one_way=true`, `direct=true`,
`unique=false`, `sorting=price`, `currency=rub`, `market=ru`, `limit=1000`, `page=1`, `token`.
Ответ декодируется существующей gzip-логикой. Тайм-аут запроса 45 секунд,
подключения — 15 секунд. URL с токеном и raw error body не печатаются.

На маршрут — максимум 3 попытки при 429 или 5xx: задержки 30/60 секунд для 429,
2/4 секунды для 5xx. После исчерпания попыток обрабатывается следующий маршрут.
401/403 прекращают новые запросы с сообщением token/access. Пустой data — EMPTY,
не ошибка. `success=false`, повреждённая структура или транспортная ошибка — FAILED.

**max-requests ограничивает реальное число HTTP-попыток, включая retry.**
Поэтому при лимите 2 и одном retry может завершиться только один маршрут.
Без лимита 20 маршрутов могут потребовать до 60 попыток при ошибках.
API requests planned показывает базовый план без retry.
Собирается только page=1 по заданию: при 1000 offers выводится предупреждение
о возможном усечении, автоматическая пагинация не запускается.

## Хранение snapshots

Только `avia.raw_prices_for_dates`, по одному документу на offer.
Все исходные поля, включая неизвестные вложенные поля, сохраняются. Если currency
есть только в корне, она копируется в offer. Поля корневого ответа кроме data
дополнительно сохраняются в `_response_metadata` (включая исходную валюту).
Пустой ответ не создаёт фиктивный offer.

Добавляются `_collected_at` (BSON Date, время получения полного ответа),
`_endpoint=prices_for_dates`, `_route=ORIGIN-DESTINATION`, `_month`, `snapshot_id`.
Идентификатор — SHA-256 от маршрута, месяца, точного collectedAt, канонического
полного offer и response metadata. Порядок JSON-полей не влияет на ID.
Разные цены/gate/link и другие исходные поля не схлопываются.
Точная копия offer в том же ответе сохраняется один раз.

Один unordered bulkWrite на непустой ответ: UpdateOneModel с `$setOnInsert` и upsert.
Unique partial index по строковому `snapshot_id` не затрагивает старые документы
Collector без этого поля. Совпавший snapshot не заменяется. При partial bulk error
учитываются подтверждённые вставки; при сетевой ошибке результат отмечается как
неподтверждённый, поскольку часть документов могла сохраниться.

Новый запуск с новым `_collected_at` — **новый snapshot**, даже если цена не изменилась.
Это не resume сбора старого snapshot. Идемпотентность означает повторную запись
того же ответа с тем же временем, а не подавление новых наблюдений цены.

## Audit

После сбора (включая частичные ошибки) читается **вся** raw_prices_for_dates,
включая старые запуски и legacy offers. Читаются только поля идентичности.
flight_statistics читается только для множества flightServiceKey; записи и индексы
этой коллекции не изменяются.

IATA приводятся к uppercase, пробелы убираются, префикс авиакомпании в номере
удаляется. Поддерживаются строковый и целочисленный flight_number. Код города/маршрут
запроса никогда не подставляется вместо отсутствующего origin_airport/destination_airport.
Даты и цены не участвуют в сопоставлении. Невалидные ключи остаются в raw и
учитываются как Not matched.

Выводятся total offers, наличие полей, число валидных ключей, matched/not matched,
match rate по всем offers и отдельно по валидным ключам, до пяти разных matched keys.
Если совпадений меньше пяти, фиктивные примеры не добавляются. При пустой коллекции
rate=N/A. Это offer-weighted доля, не доля уникальных регулярных рейсов.

Summary сбора относится к текущему запуску; audit — ко всей коллекции.
Requests completed — валидные API-ответы, включая EMPTY; Failed requests включает
ошибки получения, обработки и сохранения ответа. При ошибках exit code=1 после audit.

## Offline проверки

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.AviasalesPricesBulkCollectorTest
```

Проверяет 20 пар, параметры API на локальном mock HTTP server, gzip, request budget,
retry, EMPTY, ошибки авторизации, snapshot identity, новую цену/время, nullable/nested
raw поля, numeric flight_number, строгий join и знаменатели audit. Реальный Atlas
upsert проверяется запуском с доступными env; тест не обращается во внешнюю сеть.

При реализации MONGO_URI и AVIASALES_TOKEN отсутствовали в окружении агента:
реальные offers и match rate не измерялись. Никаких изменений в flight_statistics,
flights, AeroDataBox pipeline или visualization нет; enriched collections не создаются.
