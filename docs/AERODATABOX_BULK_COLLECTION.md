# Массовый сбор AeroDataBox FIDS в MongoDB

Main class в IntelliJ: `avia.AeroDataBoxBulkCollectorCli`.
Working directory — корень проекта. Environment variables:
`MONGO_URI` и, только для настоящего сбора, `AERODATABOX_API_KEY`.
Аргументы задаются в Program arguments.

После `mvn package` команды из терминала:

```powershell
java -cp target/avia-seasonality.jar avia.AeroDataBoxBulkCollectorCli --from 2026-10-04 --to 2026-10-24 --airports SVO,LED,AER,OVB,SVX --dry-run
java -cp target/avia-seasonality.jar avia.AeroDataBoxBulkCollectorCli --from 2026-10-04 --to 2026-10-24 --airports SVO,LED,AER,OVB,SVX --max-batches 2
java -cp target/avia-seasonality.jar avia.AeroDataBoxBulkCollectorCli --from 2026-10-04 --to 2026-10-24 --airports SVO,LED,AER,OVB,SVX
```

Даты включительны. Используется существующий FlightDatasetPlan: до 366 дней,
каждая дата/аэропорт получает окна 00–12 и 12–24. Аэропорты по умолчанию:
SVO,LED,AER,OVB,SVX. Дубликаты аэропортов и некорректные аргументы отвергаются.

Для указанного диапазона план — 210 batches. Dry-run проверяет каждый ключ в Mongo,
выводит WOULD REQUEST / SKIPPED / DEFERRED. Он не вызывает API, не пишет локальные
файлы, документы или индексы Mongo. Без MONGO_URI реальный dry-run невозможен.
Offline-тест с пустой in-memory базой: Planned=210, Already existed=0, Would request=210.
Это не результат проверки содержимого Atlas.

Перед каждым новым запросом проверяется batch_key. Существующие документы не обновляются.
Новые пишутся через `$setOnInsert` и unique batch_key. Запускайте один collector за раз:
индекс предотвращает дубли документов, но два параллельных процесса могут оба успеть
вызвать API между проверкой существования и вставкой.

`--max-batches` ограничивает число новых batches, для которых предпринимается запрос,
включая завершившиеся ошибкой. Уже существующие не расходуют лимит. Retry считаются
отдельно в API attempts: при max-batches=2 возможно до 6 попыток HTTP.
429 и 5xx: максимум 3 попытки, задержки 30/60 секунд для 429 и 2/4 секунды для 5xx.
ConnectException и HTTP timeout также повторяются ограниченно. Остальные ошибки
не повторяются. После 401/403 новые batches откладываются, чтобы не расходовать quota.
После исчерпания retry остальные batches продолжают обрабатываться. Ошибка чтения Mongo
останавливает сбор: без проверки существования нельзя безопасно тратить quota.

После ответа фиксируется реальное `collected_at` (BSON Date); такое же значение
сохраняется в `collected_at_utc` для совместимости существующего normalizer.
`ingested_at` — время подготовки документа к загрузке. Payload — полный BSON Document,
без переименования исходных полей. Ответ должен содержать массив arrivals или departures.

Тот же ответ сохраняется как UTF-8 backup в `data/raw/DATE/AIRPORT_HH-HH.json`.
Существующий файл не перезаписывается: выводится backup warning, запись Mongo продолжается.
При ошибке Mongo backup может остаться на диске; Mongo остаётся источником resume,
поэтому повторный запуск для несохранённого batch снова выполнит API-запрос.
При необходимости такой backup можно импортировать отдельно существующим raw importer,
но JSON-файл сам по себе не содержит внешнего collected_at metadata.

Никакие normalizer, preprocessor, статистика или Aviasales collections не запускаются
и не изменяются. Полный запуск расходует API quota; доступность дат определяется подпиской.

Локальные тесты после сборки:

```powershell
java -cp "target/test-classes;target/avia-seasonality.jar" avia.AeroDataBoxBulkCollectorTest
java -cp "target/test-classes;target/avia-seasonality.jar" avia.FlightPipelineTest
```

Тесты не обращаются к внешнему API или Atlas. Проверяют план, лимиты, dry-run,
resume, retry, отказ авторизации, timestamp и payload. FlightPipelineTest использует локальный mock HTTP server.
