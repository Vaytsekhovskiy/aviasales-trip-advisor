package avia;

import java.util.Map;

/**
 * Определяет человекочитаемое название города по IATA-коду.
 * Приоритет:
 *   1) название, которое уже вернул сам Aviasales API в ответе (apiName)
 *   2) справочник городов Travelpayouts (CityDictionary) — без хардкода
 *   3) сам IATA-код, если ничего не нашлось (например, нет сети)
 */
public final class DestinationNames {

    private static volatile Map<String, String> citiesByIata;
    private static final String LANG = "ru";

    private DestinationNames() {
    }

    public static String resolve(String iata, String apiName) {
        if (apiName != null && !apiName.isBlank() && !apiName.equalsIgnoreCase(iata)) {
            return apiName;
        }
        Map<String, String> cities = getCities();
        String known = cities.get(iata.toUpperCase());
        return known != null ? known : iata;
    }

    private static Map<String, String> getCities() {
        Map<String, String> local = citiesByIata;
        if (local == null) {
            synchronized (DestinationNames.class) {
                local = citiesByIata;
                if (local == null) {
                    local = safeLoad();
                    citiesByIata = local;
                }
            }
        }
        return local;
    }

    private static Map<String, String> safeLoad() {
        try {
            return CityDictionary.load(LANG);
        } catch (Exception e) {
            // Не роняем программу из-за недоступности справочника —
            // просто показываем IATA-коды вместо названий городов.
            System.err.println("Не удалось загрузить справочник городов Travelpayouts ("
                    + e.getMessage() + "), будут показаны IATA-коды.");
            return Map.of();
        }
    }
}
