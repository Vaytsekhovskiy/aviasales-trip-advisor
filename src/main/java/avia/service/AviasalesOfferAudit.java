package avia.service;

import org.bson.Document;
import java.util.*;

/** Read-only flight-level join audit, independent of price/date/duration availability. */
public final class AviasalesOfferAudit {
    public record Result(long total, long airline, long number, long airports, long validKeys,
                         long matched, long notMatched, List<String> examples) {
        public Double matchRate() { return total == 0 ? null : matched * 100.0 / total; }
        public Double validKeyMatchRate() { return validKeys == 0 ? null : matched * 100.0 / validKeys; }
    }
    public Result audit(Iterable<Document> offers, Set<String> historyKeys) {
        long total = 0, airline = 0, number = 0, airports = 0, valid = 0, matched = 0;
        SortedSet<String> examples = new TreeSet<>();
        for (Document offer : offers) {
            total++;
            String a = text(offer.get("airline")), n = number(offer.get("flight_number")),
                    o = text(offer.get("origin_airport")), d = text(offer.get("destination_airport"));
            if (a != null) airline++;
            if (n != null) number++;
            if (o != null && d != null) airports++;
            Optional<String> key = serviceKey(a, n, o, d);
            if (key.isPresent()) {
                valid++;
                if (historyKeys.contains(key.get())) {
                    matched++; examples.add(key.get());
                    if (examples.size() > 5) examples.remove(examples.last());
                }
            }
        }
        return new Result(total, airline, number, airports, valid, matched, total - matched, List.copyOf(examples));
    }
    public static Optional<String> serviceKey(String airline, String number, String origin, String destination) {
        String a = compact(airline), n = compact(number), o = compact(origin), d = compact(destination);
        if (a == null || n == null || o == null || d == null || !a.matches("[A-Z0-9]{2}")
                || !o.matches("[A-Z]{3}") || !d.matches("[A-Z]{3}")) return Optional.empty();
        if (n.startsWith(a) && n.substring(a.length()).matches("[0-9]+[A-Z]?")) n = n.substring(a.length());
        return n.matches("[0-9]+[A-Z]?") ? Optional.of(String.join("|", a, n, o, d)) : Optional.empty();
    }
    private static String compact(String s) { return s == null ? null : s.replaceAll("(?U)\\s+", "").toUpperCase(Locale.ROOT); }
    private static String text(Object value) { return value instanceof String s && !s.isBlank() ? s : null; }
    private static String number(Object value) {
        if (value instanceof String) return text(value);
        if (value instanceof Number n) {
            try { return new java.math.BigDecimal(n.toString()).toBigIntegerExact().toString(); }
            catch (ArithmeticException | NumberFormatException e) { return null; }
        }
        return null;
    }
}
