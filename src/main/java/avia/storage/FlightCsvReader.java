package avia.storage;

import avia.model.FlightRecord;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.time.*;
import java.util.*;

/** Strict CSV reader supporting quoted commas, quotes, CRLF and multiline fields. */
final class FlightCsvReader {
    static List<FlightRecord> read(Reader source) throws IOException {
        var rows = parse(source);
        if (rows.isEmpty()) throw new IOException("CSV has no header");
        List<String> header = rows.get(0);
        if (new HashSet<>(header).size() != header.size()) throw new IOException("Duplicate CSV columns");
        RecordComponent[] components = FlightRecord.class.getRecordComponents();
        int[] indices = new int[components.length];
        for (int i = 0; i < components.length; i++) {
            String name = components[i].getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
            if (name.equals("cancelled")) name = "is_cancelled";
            indices[i] = header.indexOf(name);
            if (indices[i] < 0) throw new IOException("Missing CSV column: " + name);
        }
        List<FlightRecord> result = new ArrayList<>();
        try {
            var constructor = FlightRecord.class.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            for (int row = 1; row < rows.size(); row++) {
                List<String> values = rows.get(row);
                if (values.size() != header.size()) throw new IOException("CSV row " + (row + 1) + ": incorrect column count");
                try {
                    Object[] args = new Object[components.length];
                    for (int i = 0; i < args.length; i++) args[i] = convert(values.get(indices[i]), components[i].getType());
                    FlightRecord record = constructor.newInstance(args);
                    if (record.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported schema version");
                    result.add(record);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IOException("Invalid flight CSV row " + (row + 1), e);
                }
            }
        } catch (NoSuchMethodException e) { throw new IOException("FlightRecord schema mismatch", e); }
        return List.copyOf(result);
    }

    private static Object convert(String value, Class<?> type) {
        if (value.isEmpty()) {
            if (type.isPrimitive()) throw new IllegalArgumentException("Missing required value");
            return null;
        }
        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) return Integer.valueOf(value);
        if (type == Long.class) return Long.valueOf(value);
        if (type == Double.class) return Double.valueOf(value);
        if (type == Instant.class) return Instant.parse(value);
        if (type == LocalDateTime.class) return LocalDateTime.parse(value);
        if (type == DayOfWeek.class) return DayOfWeek.valueOf(value);
        if (type == boolean.class && (value.equals("true") || value.equals("false"))) return Boolean.valueOf(value);
        throw new IllegalArgumentException("Invalid value type: " + type);
    }

    private static List<List<String>> parse(Reader source) throws IOException {
        PushbackReader reader = new PushbackReader(source, 1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false, started = false, first = true;
        int c;
        while ((c = reader.read()) != -1) {
            if (first && c == '\uFEFF') { first = false; continue; }
            first = false;
            if (quoted) {
                if (c == '"') {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else { quoted = false; closed = true; if (next != -1) reader.unread(next); }
                } else field.append((char)c);
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); closed = false; started = false;
                if (c != ',') {
                    if (c == '\r') { int next = reader.read(); if (next != '\n' && next != -1) reader.unread(next); }
                    rows.add(List.copyOf(row)); row.clear();
                }
            } else if (c == '"' && !started && !closed) {
                quoted = true; started = true;
            } else {
                if (closed || c == '"') throw new IOException("Malformed CSV quoting");
                field.append((char)c); started = true;
            }
        }
        if (quoted) throw new IOException("Unterminated CSV quote");
        if (started || closed || !row.isEmpty()) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }
}
