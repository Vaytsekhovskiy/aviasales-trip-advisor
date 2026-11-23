# Java class map before Spring Boot refactor

This inventory was prepared before moving Java sources. `avia.Main` and `avia.MongoMain` remain compatibility facades; business logic moves behind task-specific packages and services.

| Current path | Class | Responsibility | Target package |
|---|---|---|---|
| `src/main/java/avia/Main.java` | `Main` | Legacy CLI dispatch | `avia` |
| `src/main/java/avia/MongoMain.java` | `MongoMain` | Legacy prepared-data CLI dispatch across tasks 1/2/4 | `avia` |
| `src/main/java/avia/AviasalesClient.java` | `AviasalesClient` | Travelpayouts calendar, cheap and prices-for-dates HTTP client | `avia.common.aviasales` |
| `src/main/java/avia/Collector.java` | `Collector` | Shared Aviasales snapshot collector | `avia.common.aviasales.cli` |
| `src/main/java/avia/Cleaner.java` | `Cleaner` | Shared raw price normalization to `offers_clean` | `avia.common.price.cli` |
| `src/main/java/avia/AviasalesPricesBulkCollectorCli.java` | `AviasalesPricesBulkCollectorCli` | Bounded Aviasales Mongo collection CLI | `avia.common.aviasales.cli` |
| `src/main/java/avia/RouteBuilder.java` | `RouteBuilder` | Shared route-list generation | `avia.common.aviasales.cli` |
| `src/main/java/avia/CityDictionary.java` | `CityDictionary` | Shared city/IATA dictionary | `avia.common.price` |
| `src/main/java/avia/DestinationNames.java` | `DestinationNames` | Shared destination display-name lookup | `avia.common.price` |
| `src/main/java/avia/AirlineNames.java` | `AirlineNames` | Shared airline display-name lookup | `avia.common.price` |
| `src/main/java/avia/MongoSource.java` | `MongoSource` | Compatibility prepared-price reader for tasks 1/2/4 | `avia.common.price.mongo` |
| `src/main/java/avia/service/AviasalesPricesBulkCollector.java` | `AviasalesPricesBulkCollector` | Shared collection orchestration | `avia.common.aviasales` |
| `src/main/java/avia/service/AviasalesPriceSnapshots.java` | `AviasalesPriceSnapshots` | Shared lossless snapshot mapping | `avia.common.aviasales` |
| `src/main/java/avia/service/AviasalesOfferAudit.java` | `AviasalesOfferAudit` | Shared offer/history match audit | `avia.common.aviasales` |
| `src/main/java/avia/storage/MongoAviasalesPriceStorage.java` | `MongoAviasalesPriceStorage` | Raw Aviasales Mongo storage | `avia.common.aviasales.mongo` |
| `src/main/java/avia/storage/MongoBulkUpserts.java` | `MongoBulkUpserts` | Generic Mongo bulk-upsert helper | `avia.common.mongo` |
| `src/main/java/avia/client/AeroDataBoxClient.java` | `AeroDataBoxClient` | AeroDataBox HTTP transport | `avia.common.aerodatabox` |
| `src/main/java/avia/dto/AeroDataBoxFlightDto.java` | `AeroDataBoxFlightDto` | Raw AeroDataBox DTO | `avia.common.aerodatabox.dto` |
| `src/main/java/avia/AeroDataBoxBulkCollectorCli.java` | `AeroDataBoxBulkCollectorCli` | Shared resumable flight collection CLI | `avia.common.aerodatabox.cli` |
| `src/main/java/avia/AeroDataBoxRawMongoImporter.java` | `AeroDataBoxRawMongoImporter` | Shared raw JSON import CLI | `avia.common.aerodatabox.cli` |
| `src/main/java/avia/DelayCli.java` | `DelayCli` | Shared local AeroDataBox collection/preprocessing CLI | `avia.common.aerodatabox.cli` |
| `src/main/java/avia/service/AeroDataBoxBulkCollector.java` | `AeroDataBoxBulkCollector` | Shared resumable collection service | `avia.common.aerodatabox` |
| `src/main/java/avia/model/FlightBatch.java` | `FlightBatch` | Shared flight collection batch identity | `avia.common.flight.model` |
| `src/main/java/avia/model/FlightRecord.java` | `FlightRecord` | Shared normalized flight record | `avia.common.flight.model` |
| `src/main/java/avia/model/FlightHistoricalStats.java` | `FlightHistoricalStats` | Shared historical flight metrics contract | `avia.common.flight.model` |
| `src/main/java/avia/service/FlightCollector.java` | `FlightCollector` | Shared source/preprocessor/storage orchestration | `avia.common.flight` |
| `src/main/java/avia/service/FlightDatasetPlan.java` | `FlightDatasetPlan` | Shared airport/date batch plan | `avia.common.flight` |
| `src/main/java/avia/service/FlightObservationOrder.java` | `FlightObservationOrder` | Shared observation winner ordering | `avia.common.flight` |
| `src/main/java/avia/service/FlightPreprocessor.java` | `FlightPreprocessor` | Shared AeroDataBox normalization | `avia.common.flight` |
| `src/main/java/avia/service/MongoRawFlightNormalizer.java` | `MongoRawFlightNormalizer` | Shared raw-batch normalization/deduplication | `avia.common.flight` |
| `src/main/java/avia/storage/FlightReader.java` | `FlightReader` | Shared flight-read port | `avia.common.flight.storage` |
| `src/main/java/avia/storage/FlightStorage.java` | `FlightStorage` | Shared flight-write port | `avia.common.flight.storage` |
| `src/main/java/avia/storage/FlightCsvReader.java` | `FlightCsvReader` | CSV record parser | `avia.common.flight.storage` |
| `src/main/java/avia/storage/CsvFlightStorage.java` | `CsvFlightStorage` | Local CSV flight storage | `avia.common.flight.storage` |
| `src/main/java/avia/storage/MongoAeroDataBoxRawStorage.java` | `MongoAeroDataBoxRawStorage` | Raw AeroDataBox Mongo storage | `avia.common.aerodatabox.mongo` |
| `src/main/java/avia/storage/MongoFlightStorage.java` | `MongoFlightStorage` | Raw-to-normalized Mongo storage | `avia.common.flight.mongo` |
| `src/main/java/avia/storage/MongoFlightReader.java` | `MongoFlightReader` | Read-only `flights` repository | `avia.common.flight.mongo` |
| `src/main/java/avia/MongoFlightNormalizerCli.java` | `MongoFlightNormalizerCli` | Shared normalization CLI | `avia.common.flight.cli` |
| `src/main/java/avia/PriceEntry.java` | `PriceEntry` | Task 1 calendar-price model | `avia.task1.model` |
| `src/main/java/avia/SeasonalityAggregator.java` | `SeasonalityAggregator` | Task 1 month/weekday aggregation | `avia.task1.analysis` |
| `src/main/java/avia/CheapestDestinationAggregator.java` | `CheapestDestinationAggregator` | Task 1 cheapest destination by calendar cell | `avia.task1.analysis` |
| `src/main/java/avia/HeatmapHtmlRenderer.java` | `HeatmapHtmlRenderer` | Task 1 legacy HTML heatmaps | `avia.task1.report` |
| new | `Task1Service` | Task 1 reusable orchestration facade | `avia.task1.service` |
| `src/main/java/avia/TransferOffer.java` | `TransferOffer` | Task 2 direct/transfer offer model | `avia.task2.model` |
| `src/main/java/avia/TransferStats.java` | `TransferStats` | Task 2 category statistics | `avia.task2.analysis` |
| `src/main/java/avia/PairedTransferStats.java` | `PairedTransferStats` | Task 2 paired price comparison | `avia.task2.analysis` |
| `src/main/java/avia/TransferComparisonAggregator.java` | `TransferComparisonAggregator` | Task 2 direct-vs-transfer aggregation | `avia.task2.analysis` |
| `src/main/java/avia/TransferChartsRenderer.java` | `TransferChartsRenderer` | Task 2 legacy chart report | `avia.task2.report` |
| `src/main/java/avia/TransferScatterRenderer.java` | `TransferScatterRenderer` | Task 2 legacy scatter report | `avia.task2.report` |
| `src/main/java/avia/TransferComparisonHtmlRenderer.java` | `TransferComparisonHtmlRenderer` | Task 2 comparison HTML | `avia.task2.report` |
| new | `Task2Service` | Task 2 reusable analysis facade | `avia.task2.service` |
| `src/main/java/avia/service/DelayAnalyzer.java` | `DelayAnalyzer` | Task 3 descriptive delay aggregation | `avia.task3.analysis` |
| `src/main/java/avia/service/FlightHistoryAggregator.java` | `FlightHistoryAggregator` | Task 3 regular-flight descriptive history | `avia.task3.analysis` |
| `src/main/java/avia/service/DelayVisualizationAggregator.java` | `DelayVisualizationAggregator` | Task 3 dashboard data aggregation | `avia.task3.analysis` |
| `src/main/java/avia/model/DelayDashboardData.java` | `DelayDashboardData` | Task 3 dashboard result model | `avia.task3.model` |
| `src/main/java/avia/model/AviasalesFlightOffer.java` | `AviasalesFlightOffer` | Task 3 price/history enrichment offer | `avia.task3.model` |
| `src/main/java/avia/model/EnrichedFlightOffer.java` | `EnrichedFlightOffer` | Task 3 enriched offer model | `avia.task3.model` |
| `src/main/java/avia/model/OfferDashboardData.java` | `OfferDashboardData` | Task 3 offer dashboard model | `avia.task3.model` |
| `src/main/java/avia/service/AviasalesFlightPreprocessor.java` | `AviasalesFlightPreprocessor` | Task 3 offer normalization | `avia.task3.service` |
| `src/main/java/avia/service/FlightEnrichmentService.java` | `FlightEnrichmentService` | Task 3 in-memory price/history enrichment | `avia.task3.service` |
| `src/main/java/avia/service/MongoOfferEnrichmentMapper.java` | `MongoOfferEnrichmentMapper` | Task 3 Mongo offer/history mapping | `avia.task3.service` |
| `src/main/java/avia/renderer/DelayHtmlRenderer.java` | `DelayHtmlRenderer` | Task 3 local delay HTML report | `avia.task3.report` |
| `src/main/java/avia/renderer/DelayDashboardHtmlRenderer.java` | `DelayDashboardHtmlRenderer` | Task 3 Mongo delay dashboard | `avia.task3.report` |
| `src/main/java/avia/renderer/OfferDashboardHtmlRenderer.java` | `OfferDashboardHtmlRenderer` | Task 3 enriched-offer dashboard | `avia.task3.report` |
| `src/main/java/avia/storage/MongoFlightStatisticsStorage.java` | `MongoFlightStatisticsStorage` | Task 3 historical statistics storage | `avia.task3.mongo` |
| `src/main/java/avia/storage/MongoFlightEnrichmentStorage.java` | `MongoFlightEnrichmentStorage` | Task 3 enrichment storage | `avia.task3.mongo` |
| `src/main/java/avia/storage/MongoEnrichedOfferReader.java` | `MongoEnrichedOfferReader` | Task 3 prepared offer reader | `avia.task3.mongo` |
| `src/main/java/avia/DatasetCli.java` | `DatasetCli` | Task 3 collection-plan/report CLI | `avia.task3.cli` |
| `src/main/java/avia/MongoFlightStatisticsCli.java` | `MongoFlightStatisticsCli` | Task 3 statistics pipeline CLI | `avia.task3.cli` |
| `src/main/java/avia/MongoFlightEnrichmentCli.java` | `MongoFlightEnrichmentCli` | Task 3 enrichment CLI | `avia.task3.cli` |
| `src/main/java/avia/MongoDelayDashboardCli.java` | `MongoDelayDashboardCli` | Task 3 delay dashboard CLI | `avia.task3.cli` |
| `src/main/java/avia/MongoOfferDashboardCli.java` | `MongoOfferDashboardCli` | Task 3 offer dashboard CLI | `avia.task3.cli` |
| new | `Task3Service` | Task 3 reusable descriptive-analysis facade | `avia.task3.service` |
| `src/main/java/avia/AirlineStats.java` | `AirlineStats` | Task 4 airline price-policy analysis | `avia.task4.analysis` |
| `src/main/java/avia/AirlineChartsRenderer.java` | `AirlineChartsRenderer` | Task 4 legacy HTML report | `avia.task4.report` |
| new | `Task4Service` | Task 4 reusable analysis facade | `avia.task4.service` |
| `src/main/java/avia/model/FlightPredictionRequest.java` | `FlightPredictionRequest` | Common Task 5 price/delay inference request | `avia.task5.model` |
| `src/main/java/avia/service/PricePredictionService.java` | `PricePredictionService` | Task 5 Java ONNX price inference | `avia.task5.price.service` |
| new | `Task5PriceService` | Task 5 price application facade | `avia.task5.price.service` |
| `src/main/java/avia/model/FlightServiceIdentity.java` | `FlightServiceIdentity` | Task 5 delay service identity | `avia.task5.delay.model` |
| `src/main/java/avia/model/FlightProfileKey.java` | `FlightProfileKey` | Task 5 delay profile identity | `avia.task5.delay.model` |
| `src/main/java/avia/model/PredictableFlightProfile.java` | `PredictableFlightProfile` | Task 5 delay prediction-support catalog row | `avia.task5.delay.model` |
| `src/main/java/avia/service/FlightProfileAggregator.java` | `FlightProfileAggregator` | Task 5 delay/price support count aggregation | `avia.task5.delay.service` |
| `src/main/java/avia/service/PredictableFlightProfileService.java` | `PredictableFlightProfileService` | Task 5 profile eligibility | `avia.task5.delay.service` |
| `src/main/java/avia/storage/MongoProfilePriceReader.java` | `MongoProfilePriceReader` | Task 5 price-support profile reader | `avia.task5.delay.mongo` |
| `src/main/java/avia/storage/MongoPredictableFlightProfileStorage.java` | `MongoPredictableFlightProfileStorage` | Task 5 prediction-support catalog storage | `avia.task5.delay.mongo` |
| `src/main/java/avia/MongoPredictableFlightProfilesCli.java` | `MongoPredictableFlightProfilesCli` | Task 5 delay-support catalog CLI | `avia.task5.delay.cli` |

## Test source mapping

| Current tests | Responsibility | Target test package |
|---|---|---|
| `AeroDataBoxBulkCollectorTest`, `AeroDataBoxRawMongoImporterTest`, `FlightPipelineTest` | Shared AeroDataBox/flight infrastructure | `avia.common.*` |
| `AviasalesPricesBulkCollectorTest`, `MongoBulkUpsertsTest` | Shared Aviasales/Mongo infrastructure | `avia.common.*` |
| `DelayAnalysisTest`, `DelayVisualizationTest`, `FlightEnrichmentTest`, `FlightHistoryAggregatorTest`, `MongoFlightEnrichmentTest`, `MongoFlightStatisticsTest`, `OfferDashboardTest` | Task 3 descriptive analysis and reports | `avia.task3.*` |
| `MongoFlightNormalizerTest` | Shared normalized flight pipeline | `avia.common.flight` |
| `PredictableFlightProfilesTest` | Task 5 delay-support catalog | `avia.task5.delay` |

## Boundary notes

- `MongoSource` is retained as a compatibility repository during this refactor because `MongoMain` spans tasks 1, 2 and 4. New task services expose analysis independently; a later repository split can remove this cross-task facade without changing math.
- AeroDataBox access, normalized flight records and observation ordering are shared by Task 3 analytics and Task 5 delay prediction, so they belong in `avia.common`.
- Task 3 packages contain no ONNX, CatBoost or prediction-specific code.
- Python `ml/` and `models/` remain at repository root and are not moved or rewritten.
