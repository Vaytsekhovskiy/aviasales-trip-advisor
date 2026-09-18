package avia;

import java.time.LocalDate;
import java.time.DayOfWeek;

/**
 * Одна запись из ответа /v1/prices/calendar: дата вылета + цена.
 * destination может быть null/пустым в режиме запроса конкретного направления.
 */
public record PriceEntry(LocalDate date, int price, int transfers, String destination) {

    public int monthIndex() {
        return date.getMonthValue(); // 1..12
    }

    public DayOfWeek dayOfWeek() {
        return date.getDayOfWeek(); // MONDAY..SUNDAY
    }
}
