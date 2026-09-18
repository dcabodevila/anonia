package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Adds source-exact occurrences to an analysis without changing detector policy. */
final class ExactOccurrences {
    private static final String WHITESPACE = "[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern SPACE = Pattern.compile(WHITESPACE + "+");

    private ExactOccurrences() { }

    static AnonymizationPipeline.Analysis expand(AnonymizationPipeline.Analysis analysis) {
        String text = analysis.normalizedText();
        List<Detection> detections = new ArrayList<>(analysis.candidates());
        Set<Span> occupied = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Detection detection : detections) {
            occupied.add(new Span(detection.start(), detection.end()));
            ids.add(detection.id());
        }

        for (Detection source : analysis.candidates()) {
            String value = normalize(source.value());
            if (value.isEmpty()) continue;
            for (int[] range : matches(text, value)) {
                Span span = new Span(range[0], range[1]);
                if (occupied.contains(span) || joinsDigits(text, span)) continue;
                String id = "exact:" + source.id() + ":" + span.start + ":" + span.end;
                while (!ids.add(id)) id += ":";
                detections.add(new Detection(id, source.type(), span.start, span.end,
                        text.substring(span.start, span.end), source.entityKey(),
                        source.provenance(), source.confidence()));
                occupied.add(span);
            }
        }
        return new AnonymizationPipeline.Analysis(text, splitDifferentValues(detections),
                analysis.sourceSha256(), analysis.pageCount());
    }

    /** Values sharing a detector key are only one review entity when their source value is exact. */
    private static List<Detection> splitDifferentValues(List<Detection> detections) {
        List<Detection> split = new ArrayList<>();
        Set<String> entityKeys = new HashSet<>();
        detections.forEach(detection -> entityKeys.add(detection.entityKey()));
        java.util.Map<String, String> groups = new java.util.LinkedHashMap<>();
        for (Detection detection : detections) {
            String sourceValue = detection.entityKey() + "\u0000" + normalize(detection.value());
            String key = groups.get(sourceValue);
            if (key == null) {
                key = detection.entityKey();
                if (groups.containsValue(key)) {
                    key = "review:" + detection.id();
                    while (!entityKeys.add(key)) key += ":";
                }
                groups.put(sourceValue, key);
            }
            split.add(key.equals(detection.entityKey()) ? detection : new Detection(detection.id(),
                    detection.type(), detection.start(), detection.end(), detection.value(), key,
                    detection.provenance(), detection.confidence()));
        }
        return List.copyOf(split);
    }

    private static boolean joinsDigits(String source, Span span) {
        return span.start > 0 && Character.isDigit(source.codePointBefore(span.start))
                || span.end < source.length() && Character.isDigit(source.codePointAt(span.end));
    }

    private static List<int[]> matches(String source, String selected) {
        MappedSource mapped = normalizeSource(source);
        List<int[]> found = new ArrayList<>();
        for (int index = mapped.value.indexOf(selected); index >= 0;
                index = mapped.value.indexOf(selected, index + 1)) {
            int end = index + selected.length();
            Integer startOffset = mapped.offsets.get(index);
            Integer endOffset = mapped.offsets.get(end);
            if (startOffset != null && endOffset != null) found.add(new int[]{startOffset, endOffset});
        }
        return found;
    }

    private static MappedSource normalizeSource(String source) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        int whitespaceStart = -1;
        int whitespaceEnd = -1;
        for (int index = 0; index < source.length();) {
            int codePoint = source.codePointAt(index);
            int end = index + Character.charCount(codePoint);
            if (isWhitespace(codePoint)) {
                if (whitespaceStart < 0) whitespaceStart = index;
                whitespaceEnd = end;
            } else {
                if (whitespaceStart >= 0 && normalized.length() > 0) {
                    appendMapped(normalized, offsets, " ", whitespaceStart, whitespaceEnd);
                }
                whitespaceStart = -1;
                appendMapped(normalized, offsets, source.substring(index, end), index, end);
            }
            index = end;
        }
        return new MappedSource(normalized.toString(), offsets);
    }

    private static void appendMapped(StringBuilder normalized, List<Integer> offsets,
            String fragment, int sourceStart, int sourceEnd) {
        setOffset(offsets, normalized.length(), sourceStart);
        normalized.append(fragment);
        setOffset(offsets, normalized.length(), sourceEnd);
    }

    private static void setOffset(List<Integer> offsets, int index, int value) {
        while (offsets.size() <= index) offsets.add(null);
        offsets.set(index, value);
    }

    private static boolean isWhitespace(int codePoint) {
        return switch (codePoint) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
                    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
                    0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
                    0xFEFF -> true;
            default -> false;
        };
    }

    private static String normalize(String value) {
        return SPACE.matcher(value).replaceAll(" ").trim();
    }

    private record Span(int start, int end) { }
    private record MappedSource(String value, List<Integer> offsets) { }
}
