package avia.web;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

public final class RequestParameters {
    private RequestParameters() { }

    public static String optionalIata(String value) {
        return value == null || value.isBlank() ? null : code(value, "[A-Z]{3}", "Ожидается трёхбуквенный IATA-код.");
    }

    public static String requiredIata(String value) {
        String result = optionalIata(value);
        if (result == null) throw invalid("Укажите IATA-код города или аэропорта вылета.");
        return result;
    }

    public static List<String> airports(List<String> values, boolean allowIcao) {
        if (values == null) return List.of();
        return values.stream()
                .map(value -> code(value, allowIcao ? "[A-Z0-9]{3,4}" : "[A-Z]{3}",
                        allowIcao ? "Проверьте коды аэропортов IATA или ICAO." : "Проверьте IATA-коды аэропортов."))
                .distinct().toList();
    }

    public static int positive(int value, String name) {
        if (value < 1) throw invalid("Параметр «" + name + "» должен быть больше нуля.");
        return value;
    }

    public static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }

    private static String code(String value, String format, String message) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches(format)) throw invalid(message);
        return normalized;
    }
}
