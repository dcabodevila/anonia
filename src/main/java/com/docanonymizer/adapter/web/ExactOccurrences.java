package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Adds source-exact occurrences to an analysis without changing detector policy. */
final class ExactOccurrences {
    private static final String WHITESPACE = "[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern BLANK_LINE = Pattern.compile("(\r\n|\n|\r|\u2028|\u2029)[ \u00A0]*(\r\n|\n|\r|\u2028|\u2029)");
    private static final Pattern SPACE = Pattern.compile(WHITESPACE + "+");

    // Analyses this class produced. The web job store keeps the expanded analysis, so apply can
    // reuse it instead of expanding it again. Weak keys: an expired job releases its entry.
    private static final Set<AnonymizationPipeline.Analysis> EXPANDED =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private ExactOccurrences() { }

    /** Returns {@code analysis} itself when it already is the result of {@link #expand}. */
    static AnonymizationPipeline.Analysis ensureExpanded(AnonymizationPipeline.Analysis analysis) {
        return EXPANDED.contains(analysis) ? analysis : expand(analysis);
    }

    static AnonymizationPipeline.Analysis expand(AnonymizationPipeline.Analysis analysis) {
        String text = analysis.normalizedText();
        List<Detection> detections = new ArrayList<>(analysis.candidates());
        Set<Span> occupied = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Detection detection : detections) {
            occupied.add(new Span(detection.start(), detection.end()));
            ids.add(detection.id());
        }

        // The text is normalized once and each distinct value searched once: both used to be
        // repeated per candidate, which made analysis grow with candidates times text length.
        MappedSource mapped = normalizeSource(text);
        Map<String, List<int[]>> matchesByValue = new HashMap<>();
        for (Detection source : analysis.candidates()) {
            String value = normalize(source.value());
            if (value.isEmpty()) continue;
            for (int[] range : matchesByValue.computeIfAbsent(value, selected -> matches(mapped, selected))) {
                Span span = new Span(range[0], range[1]);
                if (occupied.contains(span) || joinsDigits(text, span)
                        || source.type() == DetectionType.ORGANIZATION && !organizationBoundary(text, span)
                        || source.type() == DetectionType.PERSON
                                && (!organizationBoundary(text, span) || crossesCell(text, span))) continue;
                String id = "exact:" + source.id() + ":" + span.start + ":" + span.end;
                while (!ids.add(id)) id += ":";
                detections.add(new Detection(id, source.type(), span.start, span.end,
                        text.substring(span.start, span.end), source.entityKey(),
                        source.provenance(), source.confidence()));
                occupied.add(span);
            }
        }
        AnonymizationPipeline.Analysis expanded = new AnonymizationPipeline.Analysis(text,
                splitDifferentValues(detections), analysis.sourceSha256(), analysis.pageCount());
        EXPANDED.add(expanded);
        return expanded;
    }

    /** Values sharing a detector key are only one review entity when their source value is exact. */
    private static List<Detection> splitDifferentValues(List<Detection> detections) {
        List<Detection> split = new ArrayList<>();
        Set<String> entityKeys = new HashSet<>();
        detections.forEach(detection -> entityKeys.add(detection.entityKey()));
        Map<String, String> groups = new HashMap<>();
        Set<String> assignedKeys = new HashSet<>();
        for (Detection detection : detections) {
            String sourceValue = detection.entityKey() + "\u0000" + normalize(detection.value()).toLowerCase(Locale.ROOT);
            String key = groups.get(sourceValue);
            if (key == null) {
                key = detection.entityKey();
                if (assignedKeys.contains(key)) {
                    key = "review:" + detection.id();
                    while (!entityKeys.add(key)) key += ":";
                }
                groups.put(sourceValue, key);
                assignedKeys.add(key);
            }
            split.add(key.equals(detection.entityKey()) ? detection : new Detection(detection.id(),
                    detection.type(), detection.start(), detection.end(), detection.value(), key,
                    detection.provenance(), detection.confidence()));
        }
        return List.copyOf(split);
    }

    /** A person never spans a cell (TAB) or paragraph (blank line) boundary. */
    private static boolean crossesCell(String source, Span span) {
        String covered = source.substring(span.start, span.end);
        return covered.indexOf('\t') >= 0 || BLANK_LINE.matcher(covered).find();
    }

    private static boolean organizationBoundary(String source, Span span) {
        return (span.start == 0 || !organizationWordCharacter(source.codePointBefore(span.start)))
                && (span.end == source.length() || !organizationWordCharacter(source.codePointAt(span.end)));
    }

    private static boolean organizationWordCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean joinsDigits(String source, Span span) {
        return span.start > 0 && Character.isDigit(source.codePointBefore(span.start))
                || span.end < source.length() && Character.isDigit(source.codePointAt(span.end));
    }

    private static List<int[]> matches(MappedSource mapped, String selected) {
        List<int[]> found = new ArrayList<>();
        for (int index = mapped.value.indexOf(selected); index >= 0;
                index = mapped.value.indexOf(selected, index + 1)) {
            int end = index + selected.length();
            int startOffset = mapped.offset(index);
            int endOffset = mapped.offset(end);
            if (startOffset != UNMAPPED && endOffset != UNMAPPED) found.add(new int[]{startOffset, endOffset});
        }
        return found;
    }

    /** Offset map entry for a normalized position that is not a fragment boundary. */
    private static final int UNMAPPED = -1;

    private static MappedSource normalizeSource(String source) {
        StringBuilder normalized = new StringBuilder(source.length());
        // Normalization never lengthens the text, so one slot per source char plus the end suffices.
        int[] offsets = new int[source.length() + 1];
        Arrays.fill(offsets, UNMAPPED);
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
                    appendMapped(normalized, offsets, source, whitespaceStart, whitespaceEnd, true);
                }
                whitespaceStart = -1;
                appendMapped(normalized, offsets, source, index, end, false);
            }
            index = end;
        }
        return new MappedSource(normalized.toString(), offsets);
    }

    /** Appends one fragment: a whitespace run collapsed to one space, or one code point. */
    private static void appendMapped(StringBuilder normalized, int[] offsets, String source,
            int sourceStart, int sourceEnd, boolean space) {
        offsets[normalized.length()] = sourceStart;
        if (space) normalized.append(' ');
        else normalized.append(source, sourceStart, sourceEnd);
        offsets[normalized.length()] = sourceEnd;
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
    private record MappedSource(String value, int[] offsets) {
        int offset(int index) {
            return index < offsets.length ? offsets[index] : UNMAPPED;
        }
    }
}
