package com.uhip.protocol;

import java.util.*;

/** Complete validation precedes any session mutation; integer fields reject decimals/exponents. */
public final class ControlMessage {
    private final Map<String, Object> fields;
    private ControlMessage(Map<String, Object> fields) { this.fields = fields; }

    public static ControlMessage parse(String json) {
        ControlMessage message = new ControlMessage(StrictJson.object(json));
        message.validate();
        return message;
    }

    public String type() { return string("type"); }
    public String string(String field) {
        if (!(fields.get(field) instanceof String value) || value.isEmpty()) throw invalid(field);
        return value;
    }
    public long positiveLong(String field) { return integer(field, 1, 9_007_199_254_740_991L); }
    public int nonnegativeInt(String field) { return (int) integer(field, 0, Integer.MAX_VALUE); }
    public int coordinate(String field) { return (int) integer(field, -65_535, 65_535); }
    public int positiveInt(String field) { return (int) integer(field, 1, Integer.MAX_VALUE); }
    private long integer(String field, long min, long max) {
        if (!(fields.get(field) instanceof Long value) || value < min || value > max) throw invalid(field);
        return value;
    }

    public List<String> keys(String field) {
        if (!(fields.get(field) instanceof List<?> list) || list.size() > 256) throw invalid(field);
        List<String> keys = new ArrayList<>();
        for (Object value : list) keys.add(tileKey(value));
        if (new HashSet<>(keys).size() != keys.size()) throw invalid(field);
        return keys;
    }

    public Map<String, String> results() {
        if (!(fields.get("terminalResults") instanceof Map<?, ?> map) || map.size() > 256) throw invalid("terminalResults");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            String key = tileKey(entry.getKey());
            if (!(entry.getValue() instanceof String status) || !Set.of("admitted", "discarded", "failed_decode", "omitted").contains(status)) throw invalid(key);
            result.put(key, status);
        }
        return result;
    }

    private void validate() {
        switch (type()) {
            case "HELLO" -> validateHello();
            case "SYNC_VIEW" -> validateView();
            case "BATCH_ACCEPT" -> validateAccept();
            case "BATCH_DEFER" -> { validateBatch(); string("reason"); }
            case "ACK_BATCH" -> validateAck();
            case "EVICT" -> { generation(); tileKey(string("key")); positiveLong("residencySeq"); }
            case "CREDIT_AVAILABLE" -> generation();
            case "ABORT" -> nonnegativeInt("epoch");
            case "GET_IMAGE_INFO" -> { }
            default -> throw invalid("type");
        }
    }

    private void validateHello() {
        if (!"1.0".equals(string("clientVersion")) || !"BATCH_STREAM_V2".equals(string("protocolProfile"))) throw invalid("profile");
        string("clientId"); positiveLong("maxMemoryBytes");
    }
    private void validateView() {
        nonnegativeInt("epoch"); integer("zoom", 0, 30);
        for (String field : List.of("minX", "minY", "maxX", "maxY", "centerX", "centerY")) coordinate(field);
        if (coordinate("minX") > coordinate("maxX") || coordinate("minY") > coordinate("maxY")) throw invalid("bounds");
    }
    private void validateAccept() {
        validateBatch(); positiveInt("grantId");
        if (keys("acceptedKeys").isEmpty()) throw invalid("acceptedKeys");
    }
    private void validateAck() {
        validateBatch(); nonnegativeInt("epoch"); positiveInt("grantId");
        integer("sentCount", 0, 256); integer("omittedCount", 0, 256);
        results(); keys("admittedKeys"); positiveLong("residencySeq");
    }
    private void validateBatch() { generation(); positiveInt("batchId"); }
    private void generation() { UUID.fromString(string("generationId")); }
    private String tileKey(Object value) {
        if (!(value instanceof String key) || !key.matches("[0-9]{1,2}:[0-9]{1,5}:[0-9]{1,5}")) throw invalid("key");
        return key;
    }
    private IllegalArgumentException invalid(String field) { return new IllegalArgumentException("Invalid control field: " + field); }
}
