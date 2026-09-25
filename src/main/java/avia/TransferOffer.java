package avia;

/**
 * Одно предложение из ответа /v1/prices/cheap: самая дешёвая цена на направление
 * для конкретного числа пересадок (0, 1 или 2).
 */
public record TransferOffer(String destination, String destinationName, int transfers, int price) {
}
