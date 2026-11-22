package avia.service;

import org.bson.Document;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Lossless offer snapshots; identity includes the full offer and response collection instant. */
public final class AviasalesPriceSnapshots {
    private AviasalesPriceSnapshots() { }
    public static List<Document> documents(String json, String route, YearMonth month, Instant collectedAt) {
        JSONObject root = new JSONObject(json);
        if (!root.getBoolean("success")) throw new IllegalArgumentException("Aviasales success=false");
        JSONArray offers = root.getJSONArray("data");
        // Preserve the response envelope too, without storing another copy of data in every offer.
        Document metadata = Document.parse(root.toString()); metadata.remove("data");
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < offers.length(); i++) {
            JSONObject offer = offers.getJSONObject(i);
            Document document = Document.parse(offer.toString());
            String identity = new JSONArray().put(route).put(month.toString()).put(collectedAt.toString())
                    .put(canonical(offer)).put(canonical(new JSONObject(metadata.toJson()))).toString();
            if (!document.containsKey("currency") && metadata.containsKey("currency")) document.put("currency", metadata.get("currency"));
            document.put("_collected_at", Date.from(collectedAt));
            document.put("_endpoint", "prices_for_dates");
            document.put("_route", route);
            document.put("_month", month.toString());
            document.put("_response_metadata", metadata);
            document.put("snapshot_id", hash(identity));
            result.add(document);
        }
        return List.copyOf(result);
    }

    private static String canonical(Object value) {
        if (value instanceof JSONObject object) {
            List<String> keys = new ArrayList<>(object.keySet()); Collections.sort(keys);
            List<String> parts = new ArrayList<>();
            for (String key : keys) parts.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof JSONArray array) {
            List<String> parts = new ArrayList<>(); for (Object element : array) parts.add(canonical(element));
            return "[" + String.join(",", parts) + "]";
        }
        return JSONObject.valueToString(value);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
