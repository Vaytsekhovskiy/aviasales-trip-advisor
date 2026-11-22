# Импорт raw AeroDataBox в MongoDB

Отдельный CLI читает существующие JSON, не обращается к AeroDataBox/Aviasales API
и пишет только в `avia.raw_aerodatabox`. Существующие pipeline и коллекции Aviasales не используются.

После сборки `mvn package` запуск из корня проекта:

```powershell
java -cp target/avia-seasonality.jar avia.AeroDataBoxRawMongoImporter data/raw
```

Аргумент директории необязателен: по умолчанию `data/raw`.
В IntelliJ: Main class `avia.AeroDataBoxRawMongoImporter`, Program arguments `data/raw`,
Working directory — корень проекта. В Environment variables этой конфигурации
укажите `MONGO_URI`. Переменная из конфигурации `Collector` автоматически не переносится.
`.env` не читается; `.env` и `.env.*` исключены из Git.

Импортер рекурсивно находит `.json`. Непосредственная родительская папка должна быть датой
`yyyy-MM-dd`, имя файла — `AIRPORT_HH-HH.json`. Окно — от 1 до 12 часов, конец может быть 24.
Список аэропортов и дат не задан в коде.

Пример документа (дата показана в Extended JSON):

```json
{
  "source": "AERODATABOX",
  "endpoint": "FIDS",
  "batch_key": "SVO|2026-10-03|00|12",
  "airport": "SVO",
  "date": "2026-10-03",
  "from_hour": 0,
  "to_hour": 12,
  "ingested_at": {"$date": "2026-10-25T12:00:00Z"},
  "payload": {"arrivals": [], "departures": []}
}
```

В реальном документе `payload` содержит полный исходный объект, включая вложенные массивы.
Время `ingested_at` отражает загрузку, не историческое время сбора.
Уникальный индекс `batch_key` и `replaceOne(upsert=true)` предотвращают дубли.
Повторный импорт заменяет документ и обновляет `ingested_at`; такие записи считаются `Updated`.
Других индексов импортер не создаёт.

Ошибка отдельного файла не останавливает остальные. В конце выводятся счётчики и пути
ошибочных файлов с типами ошибок. При ошибках код завершения — 1.
При невозможности инициализировать storage импорт не начинается. Тексты исключений драйвера
не выводятся, чтобы не раскрыть параметры подключения.

Локальные тесты без Atlas, после `mvn test-compile`:

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.AeroDataBoxRawMongoImporterTest
# Дополнительно проверить все локальные JSON, без записи в MongoDB:
java -cp "target/test-classes;target/avia-seasonality.jar" avia.AeroDataBoxRawMongoImporterTest data/raw
```

Тест проверяет пути, BSON payload, timestamp, повторные записи через in-memory sink,
продолжение после ошибок пути, JSON и записи. Настоящая работа MongoDB и индекса
не проверяется этими offline-тестами.
