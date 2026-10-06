package com.uhip.protocol;

import java.math.BigDecimal;
import java.util.*;

/** Bounded JSON reader. Duplicate members and trailing input are rejected. */
public final class StrictJson {
    private final String text;
    private int cursor;

    private StrictJson(String text) {
        if (text == null || text.length() > 65_536) throw invalid();
        this.text = text;
    }

    public static Map<String, Object> object(String text) {
        StrictJson reader = new StrictJson(text);
        Object value = reader.value(0);
        reader.whitespace();
        if (reader.cursor != text.length() || !(value instanceof Map<?, ?>)) throw invalid();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private Object value(int depth) {
        if (depth > 12) throw invalid();
        whitespace();
        return switch (peek()) {
            case '{' -> members(depth + 1);
            case '[' -> elements(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> members(int depth) {
        cursor++;
        Map<String, Object> result = new LinkedHashMap<>();
        if (consume('}')) return result;
        do { readMember(result, depth); } while (consume(','));
        require('}');
        return result;
    }

    private void readMember(Map<String, Object> result, int depth) {
        whitespace();
        String key = string();
        if (result.size() >= 1024 || result.containsKey(key)) throw invalid();
        require(':');
        result.put(key, value(depth));
    }

    private List<Object> elements(int depth) {
        cursor++;
        List<Object> result = new ArrayList<>();
        if (consume(']')) return result;
        do {
            if (result.size() >= 1024) throw invalid();
            result.add(value(depth));
        } while (consume(','));
        require(']');
        return result;
    }

    private String string() {
        if (peek() != '"') throw invalid();
        cursor++;
        StringBuilder result = new StringBuilder();
        while (peek() != '"') {
            char next = take();
            if (next < 32 || result.length() >= 4096) throw invalid();
            result.append(next == '\\' ? escape() : next);
        }
        cursor++;
        return result.toString();
    }

    private char escape() {
        return switch (take()) {
            case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
            case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
            case 'r' -> '\r'; case 't' -> '\t'; case 'u' -> unicode();
            default -> throw invalid();
        };
    }

    private char unicode() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(take(), 16);
            if (digit < 0) throw invalid();
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Object number() {
        int start = cursor;
        if (peek() == '-') cursor++;
        if (peek() == '0') cursor++; else digits();
        boolean integral = readFractionAndExponent();
        if (cursor - start > 64) throw invalid();
        String token = text.substring(start, cursor);
        try { return integral ? (Object) Long.valueOf(token) : new BigDecimal(token); }
        catch (NumberFormatException ex) { throw invalid(); }
    }

    private boolean readFractionAndExponent() {
        boolean integral = true;
        if (peek() == '.') { cursor++; digits(); integral = false; }
        if (peek() == 'e' || peek() == 'E') {
            cursor++;
            if (peek() == '+' || peek() == '-') cursor++;
            digits(); integral = false;
        }
        return integral;
    }

    private void digits() {
        int start = cursor;
        while (peek() >= '0' && peek() <= '9') cursor++;
        if (cursor == start) throw invalid();
    }

    private Object literal(String token, Object value) {
        if (!text.startsWith(token, cursor)) throw invalid();
        cursor += token.length();
        return value;
    }

    private char peek() { return cursor < text.length() ? text.charAt(cursor) : '\0'; }
    private char take() { if (cursor >= text.length()) throw invalid(); return text.charAt(cursor++); }
    private void whitespace() { while (" \r\n\t".indexOf(peek()) >= 0 && cursor < text.length()) cursor++; }
    private boolean consume(char expected) {
        whitespace();
        if (peek() != expected) return false;
        cursor++; return true;
    }
    private void require(char expected) { if (!consume(expected)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or oversized JSON"); }
}
