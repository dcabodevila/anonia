package com.docanonymizer.adapter.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Serializador JSON minimo.
 *
 * <p>Escrito a mano en lugar de anadir Jackson o Gson porque el prototipo debe compilar
 * y ejecutarse sin acceso a la red, y la superficie que necesita es esta: objetos,
 * arrays, cadenas escapadas y numeros. Meter una dependencia de 2 MB para eso seria
 * pagar mucho por muy poco.
 */
public final class Json {

    private Json() {
    }

    public static String object(Map<String, String> rawFields) {
        return rawFields.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    public static <T> String array(List<T> items, Function<T, String> toJson) {
        return items.stream().map(toJson).collect(Collectors.joining(",", "[", "]"));
    }

    public static String strings(List<String> values) {
        return array(values, Json::quote);
    }

    public static String number(long value) {
        return Long.toString(value);
    }

    public static String number(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public static String bool(boolean value) {
        return Boolean.toString(value);
    }

    /** Escapa segun RFC 8259, incluidos los caracteres de control por debajo de 0x20. */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
