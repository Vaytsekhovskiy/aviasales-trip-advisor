package avia.web;

import avia.common.aerodatabox.AeroDataBoxBulkCollector;
import avia.common.aerodatabox.AeroDataBoxClient;
import avia.common.aerodatabox.mongo.MongoAeroDataBoxRawStorage;
import avia.common.aviasales.AviasalesClient;
import avia.common.aviasales.AviasalesPricesBulkCollector;
import avia.common.aviasales.mongo.MongoAviasalesPriceStorage;
import avia.common.flight.FlightDatasetPlan;
import avia.common.flight.MongoRawFlightNormalizer;
import avia.common.flight.model.FlightBatch;
import avia.common.flight.mongo.MongoFlightReader;
import avia.common.flight.mongo.MongoFlightStorage;
import avia.common.price.cli.Cleaner;
import avia.task2.analysis.PairedTransferStats;
import avia.task4.analysis.AirlineStats;
import avia.task5.delay.mongo.MongoPredictableFlightProfileStorage;
import avia.task5.delay.mongo.MongoProfilePriceReader;
import avia.task5.delay.service.FlightProfileAggregator;
import avia.task5.delay.service.Task5DelayProfileService;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static avia.web.PipelineJobs.Stage.*;
import static avia.web.RequestParameters.*;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Service
class TaskPipelines {
    private static final String CALENDAR = "calendar";
    private static final String PRICES = "prices_for_dates";
    private static final String RAW_CALENDAR = "raw_calendar";

    public record Parameters(String origin, List<String> destinations, YearMonth month,
                             LocalDate from, LocalDate to, List<String> airports,
                             Integer maxRequests, Integer minDelayFlights, Integer minPriceFlights,
                             Boolean collect) { }

    private record PriceScope(String origin, List<String> destinations, YearMonth month) {
        List<AviasalesPricesBulkCollector.Route> routes() {
            return destinations.stream().map(destination -> new AviasalesPricesBulkCollector.Route(origin, destination)).toList();
        }
    }

    private record CollectionResult(int saved, int skipped, int failed) { }

    private final MongoDatabase database;
    private final Clock clock;
    private final PreparedAnalytics analytics;
    private final Task5DelayProfileService profiles;
    private final ObjectProvider<AviasalesClient> aviasales;
    private final ObjectProvider<AeroDataBoxClient> aeroDataBox;

    TaskPipelines(@Lazy MongoDatabase database, Clock clock, PreparedAnalytics analytics,
                  Task5DelayProfileService profiles, ObjectProvider<AviasalesClient> aviasales,
                  ObjectProvider<AeroDataBoxClient> aeroDataBox) {
        this.database = database;
        this.clock = clock;
        this.analytics = analytics;
        this.profiles = profiles;
        this.aviasales = aviasales;
        this.aeroDataBox = aeroDataBox;
    }

    PipelineJobs.Work plan(int task, Parameters params) {
        boolean collect = !Boolean.FALSE.equals(params.collect());
        return switch (task) {
            case 1, 2, 4 -> planPrice(task, params, collect);
            case 3 -> planDelays(params, collect);
            case 5 -> planProfiles(params, collect);
            default -> throw new ResponseStatusException(NOT_FOUND, "Задача не найдена.");
        };
    }

    private PipelineJobs.Work planPrice(int task, Parameters params, boolean collect) {
        PriceScope scope = priceScope(params, collect, task == 1);
        int budget = budget(params.maxRequests(), Math.max(1, scope.destinations().size() * 3));
        AviasalesClient client = collect ? requireAviasales() : null;
        return progress -> {
            String source = task == 1 ? CALENDAR : PRICES;
            int prepared = updatePriceData(scope, budget, source, client, false, progress);
            progress.update(ANALYZING, "Рассчитываем показатели по обновлённым данным.");
            switch (task) {
                case 1 -> analytics.seasonality(scope.origin(), null, scope.month());
                case 2 -> analytics.transfers(scope.origin(), scope.month(), PairedTransferStats.DEFAULT_MIN_PAIRS, false);
                case 4 -> analytics.airlines(scope.origin(), scope.month(), true, false,
                        AirlineStats.DEFAULT_MIN_PAIRS, AirlineStats.DEFAULT_MIN_DESTINATIONS);
                default -> throw new IllegalStateException("Unexpected price task");
            }
            return "Подготовлено наблюдений: " + prepared + ".";
        };
    }

    private PipelineJobs.Work planDelays(Parameters params, boolean collect) {
        List<String> airports = airports(params.airports(), collect);
        LocalDate from = params.from(), to = params.to();
        if (from == null || to == null || airports.isEmpty() || airports.size() > 20) {
            throw invalid("Укажите период и от 1 до 20 аэропортов.");
        }
        if (to.isAfter(LocalDate.now(clock))) throw invalid("Укажите период исторических наблюдений.");
        List<FlightBatch> batches;
        try {
            batches = FlightDatasetPlan.batches(from, to, airports);
        } catch (IllegalArgumentException error) {
            throw invalid("Период сбора должен содержать от 1 до 366 дней.");
        }
        int budget = budget(params.maxRequests(), Math.min(batches.size(), 20));
        AeroDataBoxClient client = collect ? aeroDataBox.getIfAvailable() : null;
        if (collect && client == null) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Для сбора задайте AERODATABOX_API_KEY в настройках backend.");
        }
        return progress -> {
            progress.update(COLLECTING, collect ? "Получаем окна рейсов AeroDataBox." : "Используем сохранённые окна AeroDataBox.");
            var result = client == null ? null : new AeroDataBoxBulkCollector().run(batches, false, budget,
                    new MongoAeroDataBoxRawStorage(database), client::fetchFlights, Thread::sleep,
                    (batch, json) -> { /* MongoDB stores the original payload. */ }, clock,
                    message -> progress.update(COLLECTING, message));
            progress.update(PREPROCESSING, "Нормализуем рейсы и устраняем дубли по всей истории.");
            int flights = normalizeFlights();
            progress.update(ANALYZING, "Рассчитываем описательную статистику задержек.");
            var selected = airports.stream().filter(code -> code.length() == 3).toList();
            int count = analytics.delays(from, to, selected, 10).flightCount();
            if (result != null && !result.failures().isEmpty()) {
                throw failed("Не удалось получить окон AeroDataBox: " + result.failures().size()
                        + ". Успешно полученные окна обработаны.");
            }
            String deferred = result == null || result.deferred() == 0 ? ""
                    : " Окон сверх лимита: " + result.deferred() + ".";
            return "Рейсов в отчёте: " + count + ". Подготовлено в общей истории: " + flights + "." + deferred;
        };
    }

    private PipelineJobs.Work planProfiles(Parameters params, boolean collect) {
        int minDelay = positive(Objects.requireNonNullElse(params.minDelayFlights(), 3), "Рейсов с задержками");
        int minPrice = positive(Objects.requireNonNullElse(params.minPriceFlights(), 3), "Рейсов с ценами");
        PriceScope scope = collect ? priceScope(params, true, false) : null;
        int budget = budget(params.maxRequests(), scope == null ? 1 : scope.destinations().size() * 3);
        AviasalesClient client = collect ? requireAviasales() : null;
        return progress -> {
            if (client != null) {
                updatePriceData(scope, budget, PRICES, client, true, progress);
            } else {
                progress.update(COLLECTING, "Используем сохранённые наблюдения цены и задержки.");
            }
            progress.update(PREPROCESSING, "Считаем покрытие профилей в двух источниках.");
            var history = new MongoFlightReader(database).read(Filters.lt("scheduledDepartureUtc", Date.from(clock.instant())));
            var delayCounts = profiles.aggregateDelayHistory(history);
            var priceCounts = new FlightProfileAggregator().priceCounts(new MongoProfilePriceReader().readAll(database));
            progress.update(ANALYZING, "Обновляем общий каталог профилей.");
            var eligible = profiles.selectPredictable(delayCounts, priceCounts, minDelay, minPrice);
            new MongoPredictableFlightProfileStorage().rebuild(database, eligible);
            return "В общем каталоге профилей: " + eligible.size() + ".";
        };
    }

    private int normalizeFlights() {
        var storage = new MongoFlightStorage(database);
        MongoRawFlightNormalizer.Result result;
        try (var cursor = storage.rawBatches()) {
            result = new MongoRawFlightNormalizer().normalize(() -> cursor);
        }
        if (!result.failures().isEmpty()) {
            throw failed("Не удалось разобрать окон AeroDataBox: " + result.failures().size() + ".");
        }
        var writes = storage.saveAll(result.flights());
        if (!writes.failures().isEmpty() || writes.uncertain() > 0) throw failed("MongoDB не подтвердила часть записей рейсов.");
        return result.flights().size();
    }

    private int updatePriceData(PriceScope scope, int budget, String source, AviasalesClient client,
                                boolean directOnly, PipelineJobs.Progress progress) throws InterruptedException {
        progress.update(COLLECTING, client == null ? "Используем сохранённые ценовые наблюдения." : "Запрашиваем цены Aviasales.");
        Date started = Date.from(clock.instant());
        CollectionResult result = client == null ? new CollectionResult(0, 0, 0)
                : CALENDAR.equals(source) ? collectCalendar(scope, budget, client, progress)
                : collectPrices(scope, budget, client, directOnly, progress);

        progress.update(PREPROCESSING, "Очищаем наблюдения и сохраняем их в offers_clean.");
        List<Bson> filters = new ArrayList<>();
        if (scope.destinations().isEmpty()) filters.add(Filters.regex("_route", "^" + scope.origin() + "-"));
        else filters.add(Filters.in("_route", scope.routes().stream().map(AviasalesPricesBulkCollector.Route::key).toList()));
        if (client != null) filters.add(Filters.gte("_collected_at", started));
        if (scope.month() != null) filters.add(Filters.regex("departure_at", "^" + scope.month() + "-"));
        String collection = CALENDAR.equals(source) ? RAW_CALENDAR : MongoAviasalesPriceStorage.COLLECTION;
        int prepared;
        try (var observations = database.getCollection(collection).find(Filters.and(filters)).iterator()) {
            prepared = Cleaner.cleanObservations(database, source, () -> observations);
        }
        if (result.failed() > 0 || result.skipped() > 0) {
            throw failed("Aviasales сохранил наблюдений: " + result.saved() + ". Ошибок: " + result.failed()
                    + ", маршрутов сверх лимита: " + result.skipped() + ". Доступные наблюдения подготовлены.");
        }
        return prepared;
    }

    private CollectionResult collectPrices(PriceScope scope, int budget, AviasalesClient client,
                                          boolean directOnly, PipelineJobs.Progress progress) throws InterruptedException {
        var storage = new MongoAviasalesPriceStorage(database);
        var routes = scope.routes();
        var result = new AviasalesPricesBulkCollector().run(routes, scope.month(), budget,
                route -> client.fetchPricesForDates(route.origin(), route.destination(), scope.month(), directOnly),
                storage::save, Thread::sleep, clock, message -> progress.update(COLLECTING, message));
        int skipped = Math.max(0, routes.size() - result.completed() - result.failures().size());
        return new CollectionResult(result.saved(), skipped, result.failures().size());
    }

    private CollectionResult collectCalendar(PriceScope scope, int budget, AviasalesClient client,
                                             PipelineJobs.Progress progress) throws InterruptedException {
        int saved = 0, failed = 0, attempts = 0;
        var collection = database.getCollection(RAW_CALENDAR);
        for (var route : scope.routes()) {
            if (attempts >= budget) break;
            attempts++;
            progress.update(COLLECTING, "Запрашиваем календарь по маршруту " + route.key() + ".");
            try {
                var data = client.fetchCalendarData(route.origin(), route.destination(), scope.month(), "rub");
                if (!data.isEmpty()) {
                    Date captured = Date.from(clock.instant());
                    List<Document> batch = new ArrayList<>();
                    for (String date : data.keySet()) {
                        var offer = data.getJSONObject(date);
                        var document = Document.parse(offer.toString());
                        document.putIfAbsent("departure_at", date);
                        batch.add(document.append("_collected_at", captured).append("_endpoint", RAW_CALENDAR)
                                .append("_route", route.key()));
                    }
                    collection.insertMany(batch);
                    saved += batch.size();
                }
            } catch (IOException | RuntimeException error) {
                failed++;
                if (error instanceof AviasalesClient.HttpStatusException status
                        && (status.status() == 401 || status.status() == 403)) break;
            }
            if (attempts < scope.destinations().size() && attempts < budget) Thread.sleep(300);
        }
        return new CollectionResult(saved, scope.destinations().size() - attempts, failed);
    }

    private static PriceScope priceScope(Parameters params, boolean collect, boolean optionalMonth) {
        String origin = requiredIata(params.origin());
        List<String> destinations = airports(params.destinations(), false);
        if (destinations.size() > 20 || collect && (destinations.isEmpty() || destinations.contains(origin))) {
            throw invalid("Укажите от 1 до 20 пунктов назначения, отличных от пункта вылета.");
        }
        if (collect && !optionalMonth && params.month() == null) throw invalid("Укажите месяц вылета.");
        return new PriceScope(origin, destinations, params.month());
    }

    private AviasalesClient requireAviasales() {
        AviasalesClient client = aviasales.getIfAvailable();
        if (client == null) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Для сбора задайте AVIASALES_TOKEN в настройках backend.");
        return client;
    }

    private static int budget(Integer supplied, int defaultBudget) {
        int value = positive(Objects.requireNonNullElse(supplied, defaultBudget), "Лимит загрузки");
        if (value > 200) throw invalid("Лимит загрузки должен быть не больше 200.");
        return value;
    }

    private static ResponseStatusException failed(String message) {
        return new ResponseStatusException(SERVICE_UNAVAILABLE, message);
    }
}
