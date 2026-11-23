package avia.task5.price.service;

import ai.onnxruntime.*;

import avia.task5.model.FlightPredictionRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

public class PricePredictionService implements AutoCloseable {

    private static final String DEFAULT_MODEL_PATH = "src/main/resources/models/price_model.onnx";

    private final OrtEnvironment environment;
    private final OrtSession session;

    public PricePredictionService() throws Exception {
        this(Path.of(System.getenv().getOrDefault("PRICE_MODEL_PATH", DEFAULT_MODEL_PATH)));
    }

    public PricePredictionService(Path model) throws Exception {
        if (!Files.isRegularFile(model)) throw new IllegalStateException("Не найдена ONNX-модель: " + model);
        this.environment = OrtEnvironment.getEnvironment();
        try (var options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            this.session = environment.createSession(model.toAbsolutePath().toString(), options);
        }
    }

    public float predictPrice(
            FlightPredictionRequest request
    ) throws Exception {

        // Локальное время вылета с сохранением UTC offset
        OffsetDateTime departure =
                OffsetDateTime.parse(request.scheduledDeparture());

        // Момент формирования прогноза
        OffsetDateTime predictionTime =
                OffsetDateTime.parse(request.predictionTimeUtc());

        // Календарные признаки берём из локального времени вылета
        float departureMonth = departure.getMonthValue();
        float departureDay = departure.getDayOfMonth();
        float departureHour = departure.getHour();

        // Разницу вычисляем между абсолютными моментами времени
        float daysBefore = (float) (
                Duration.between(
                        predictionTime.toInstant(),
                        departure.toInstant()
                ).toMillis() / 86_400_000.0
        );

        if (daysBefore <= 0) {
            throw new IllegalArgumentException(
                    "Время вылета должно быть позже времени прогноза"
            );
        }

        // Java: Monday = 1 ... Sunday = 7
        // Python: Monday = 0 ... Sunday = 6
        long departureWeekday =
                departure.getDayOfWeek().getValue() - 1;

        Map<String, OnnxTensor> inputs = new HashMap<>();

        try {
            inputs.put(
                    "departure_month",
                    createFloatTensor(departureMonth)
            );

            inputs.put(
                    "departure_day",
                    createFloatTensor(departureDay)
            );

            inputs.put(
                    "departure_hour",
                    createFloatTensor(departureHour)
            );

            inputs.put(
                    "days_before",
                    createFloatTensor(daysBefore)
            );

            inputs.put(
                    "airline",
                    createStringTensor(request.airlineIata())
            );

            inputs.put(
                    "flight_number",
                    createStringTensor(request.flightNumber())
            );

            inputs.put(
                    "origin_airport",
                    createStringTensor(request.originIata())
            );

            inputs.put(
                    "destination_airport",
                    createStringTensor(request.destinationIata())
            );

            inputs.put(
                    "departure_weekday",
                    createLongTensor(departureWeekday)
            );

            try (OrtSession.Result result = session.run(inputs)) {

                float[][] output =
                        (float[][]) result.get("variable")
                                .orElseThrow()
                                .getValue();

                return output[0][0];
            }

        } finally {
            for (OnnxTensor tensor : inputs.values()) {
                tensor.close();
            }
        }
    }

    private OnnxTensor createFloatTensor(float value)
            throws OrtException {

        return OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(new float[]{value}),
                new long[]{1, 1}
        );
    }

    private OnnxTensor createLongTensor(long value)
            throws OrtException {

        return OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(new long[]{value}),
                new long[]{1, 1}
        );
    }

    private OnnxTensor createStringTensor(String value)
            throws OrtException {

        return OnnxTensor.createTensor(
                environment,
                new String[][]{{value}}
        );
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
