package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Replays a bounded, versioned edit log against the stored analysis, never client keys. */
final class ReviewEdits {
    // ECMAScript whitespace: keep browser normalization and UTF-16 ranges identical.
    private static final String WHITESPACE = "[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";
    private static final Pattern SPACE = Pattern.compile(WHITESPACE + "+");
    private final Map<String, Detection> detections = new LinkedHashMap<>();
    private final Map<String, Detection> originals = new LinkedHashMap<>();
    private final Map<String, Entity> entities = new LinkedHashMap<>();
    private final String text;
    private final Map<String, Integer> editedOrder = new LinkedHashMap<>();
    private int editSequence;

    private static final class Entity {
        DetectionType type;
        boolean rejected;
        final List<String> ids = new ArrayList<>();
        Entity(Detection d) { type = d.type(); }
    }

    private ReviewEdits(AnonymizationPipeline.Analysis analysis) {
        analysis = ExactOccurrences.expand(analysis);
        text = analysis.normalizedText();
        Map<String, String> sourceValueGroups = new LinkedHashMap<>();
        var entityKeys = new java.util.HashSet<String>();
        analysis.candidates().forEach(d -> entityKeys.add(d.entityKey()));
        for (Detection candidate : analysis.candidates()) {
            String sourceValue = candidate.entityKey() + "\u0000" + normalize(candidate.value());
            String key = sourceValueGroups.get(sourceValue);
            if (key == null) {
                key = candidate.entityKey();
                if (sourceValueGroups.containsValue(key)) {
                    key = "review:" + candidate.id();
                    while (!entityKeys.add(key)) key += ":";
                }
                sourceValueGroups.put(sourceValue, key);
            }
            Detection d = key.equals(candidate.entityKey()) ? candidate : new Detection(candidate.id(),
                    candidate.type(), candidate.start(), candidate.end(), candidate.value(), key,
                    candidate.provenance(), candidate.confidence());
            detections.put(d.id(), d);
            originals.put(d.id(), d);
            Entity entity = entities.computeIfAbsent(d.entityKey(), ignored -> new Entity(d));
            entity.ids.add(d.id());
        }
    }

    static List<Detection> apply(AnonymizationPipeline.Analysis analysis, String body) {
        return replay(analysis, body).effective();
    }

    static AnonymizationPipeline.ReviewedSelection replay(AnonymizationPipeline.Analysis analysis, String body) {
        ReviewEdits review = new ReviewEdits(analysis);
        if (body.startsWith("review-v1\n")) {
            String[] lines = body.substring(10).split("\n", -1);
            if (lines.length > 10000) throw invalid();
            if (!body.equals("review-v1\n")) {
                for (String line : lines) review.edit(line);
            }
        } else {
            // Compatibility with the original rejected-ID endpoint, with ID validation.
            for (String id : body.split("[,\\s]+")) {
                if (id.isEmpty()) continue;
                Detection d = review.detections.remove(id);
                if (d == null) throw invalid();
            }
            var rejectedIds = new java.util.HashSet<>(review.originals.keySet());
            rejectedIds.removeAll(review.detections.keySet());
            return new AnonymizationPipeline.ReviewedSelection(
                    review.effective(List.copyOf(review.detections.values())), rejectedIds);
        }
        List<Detection> accepted = new ArrayList<>();
        var rejectedIds = new java.util.HashSet<String>();
        for (Detection d : review.detections.values()) {
            if (!review.entities.get(d.entityKey()).rejected) accepted.add(d);
            else rejectedIds.add(d.id());
        }
        return new AnonymizationPipeline.ReviewedSelection(review.effective(accepted), rejectedIds);
    }

    private void edit(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 3) throw invalid();
        String id = decode(fields[1]);
        String value = decode(fields[2]);
        if (fields[0].equals("add")) {
            addManual(id, value);
            return;
        }
        Detection anchor = detections.get(id);
        if (anchor == null) throw invalid();
        String key = anchor.entityKey();
        Entity entity = entities.get(key);
        switch (fields[0]) {
            case "reject" -> {
                if (!value.equals("true") && !value.equals("false")) throw invalid();
                entity.rejected = Boolean.parseBoolean(value);
            }
            case "type" -> {
                entity.type = Arrays.stream(DetectionType.values())
                        .filter(type -> type.label().equals(value)).findFirst().orElseThrow(ReviewEdits::invalid);
                for (String member : entity.ids) replace(member, key, entity.type, null);
            }
            case "text" -> {
                String selected = normalize(value);
                if (selected.isEmpty() || !selected.equals(value)) throw invalid();
                editText(id, entity, selected);
            }
            default -> throw invalid();
        }
    }

    /** Adds only server-derived, exact source ranges for a browser-created manual entity. */
    private void addManual(String id, String value) {
        String selected = normalize(value);
        if (!id.matches("manual:[1-9]\\d*") || selected.isEmpty() || !selected.equals(value)
                || detections.containsKey(id) || entities.containsKey(id)) throw invalid();
        List<int[]> ranges = matches(text, selected).stream()
                .filter(range -> !joinsDigits(text, range)).toList();
        if (ranges.isEmpty()) throw invalid();
        for (int index = 0; index < ranges.size(); index++) {
            int[] range = ranges.get(index);
            if (!hasNoPartialOverlap(null, range)) throw invalid();
            for (int earlier = 0; earlier < index; earlier++) {
                if (hasPartialOverlap(range, ranges.get(earlier))) throw invalid();
            }
        }

        Entity entity = null;
        for (int index = 0; index < ranges.size(); index++) {
            int[] range = ranges.get(index);
            String detectionId = index == 0 ? id : id + ":" + range[0] + ":" + range[1];
            if (detections.containsKey(detectionId)) throw invalid();
            Detection detection = new Detection(detectionId, DetectionType.CODIGO,
                    range[0], range[1], text.substring(range[0], range[1]), id,
                    Provenance.MANUAL, 1.0);
            detections.put(detectionId, detection);
            originals.put(detectionId, detection);
            if (entity == null) {
                entity = new Entity(detection);
                entities.put(id, entity);
            }
            entity.ids.add(detectionId);
        }
    }

    private void editText(String id, Entity entity, String selected) {
        String oldValue = normalize(detections.get(id).value());
        Map<String, int[]> ranges = new LinkedHashMap<>();
        for (String member : entity.ids) {
            if (!normalize(detections.get(member).value()).equals(oldValue)) continue;
            int[] range = anchoredRange(originals.get(member), selected);
            if (range == null) continue;
            if (!hasNoPartialOverlap(member, range)) {
                if (member.equals(id)) throw new IllegalArgumentException(
                        "La seleccion solapa parcialmente otra deteccion. Incluyela completa o reduce la seleccion. No se guardo el cambio.");
                continue;
            }
            ranges.put(member, range);
        }
        if (!ranges.containsKey(id)) throw new IllegalArgumentException(
                "Selecciona un fragmento contiguo del documento que incluya la deteccion original o este dentro de ella.");
        for (Map.Entry<String, int[]> change : ranges.entrySet()) {
            replace(change.getKey(), detections.get(change.getKey()).entityKey(), entity.type,
                    change.getValue());
            editedOrder.put(change.getKey(), ++editSequence);
        }
    }

    /** Project accepted retained tags: outermost first, latest edit wins equal coordinates.
     * Stable analysis order breaks ties between otherwise identical, unedited spans. */
    private List<Detection> effective(List<Detection> accepted) {
        List<Detection> ordered = new ArrayList<>(accepted);
        ordered.sort(Comparator.comparingInt(Detection::start)
                .thenComparing(Comparator.comparingInt(Detection::end).reversed())
                .thenComparing(Comparator.comparingInt((Detection d) -> editedOrder.getOrDefault(d.id(), 0)).reversed()));
        List<Detection> result = new ArrayList<>();
        int end = -1;
        for (Detection detection : ordered) {
            if (detection.start() >= end) {
                result.add(detection);
                end = detection.end();
            }
        }
        return List.copyOf(result);
    }

    private boolean hasNoPartialOverlap(String id, int[] range) {
        for (Detection other : detections.values()) {
            if (id != null && other.id().equals(id)) continue;
            if (hasPartialOverlap(range, new int[]{other.start(), other.end()})) return false;
        }
        return true;
    }

    private static boolean hasPartialOverlap(int[] first, int[] second) {
        if (first[0] >= second[1] || second[0] >= first[1]) return false;
        boolean contains = first[0] <= second[0] && first[1] >= second[1];
        boolean contained = second[0] <= first[0] && second[1] >= first[1];
        return !contains && !contained;
    }

    /** Narrow inside the immutable occurrence, or expand to a unique source match enclosing it. */
    private int[] anchoredRange(Detection original, String selected) {
        int[] inside = narrow(text.substring(original.start(), original.end()), selected);
        if (inside != null) return new int[]{original.start() + inside[0], original.start() + inside[1]};
        List<int[]> enclosing = matches(text, selected).stream()
                .filter(range -> range[0] <= original.start() && range[1] >= original.end()).toList();
        return enclosing.size() == 1 ? enclosing.get(0) : null;
    }

    private void replace(String id, String key, DetectionType type, int[] range) {
        Detection old = detections.get(id);
        int start = range == null ? old.start() : range[0];
        int end = range == null ? old.end() : range[1];
        detections.put(id, new Detection(id, type, start, end, text.substring(start, end),
                key, old.provenance(), old.confidence()));
    }

    /** Match exact contiguous normalized source fragments, retaining original UTF-16 offsets. */
    static int[] narrow(String source, String selected) {
        List<int[]> found = matches(source, selected);
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean joinsDigits(String source, int[] range) {
        return range[0] > 0 && Character.isDigit(source.codePointBefore(range[0]))
                || range[1] < source.length() && Character.isDigit(source.codePointAt(range[1]));
    }

    private static List<int[]> matches(String source, String selected) {
        MappedSource mapped = normalizeSource(source);
        List<int[]> found = new ArrayList<>();
        for (int index = mapped.value.indexOf(selected); index >= 0; index = mapped.value.indexOf(selected, index + 1)) {
            int end = index + selected.length();
            Integer startOffset = mapped.offsets.get(index);
            Integer endOffset = mapped.offsets.get(end);
            // Never return a range that splits a supplementary character's UTF-16 pair.
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

    private record MappedSource(String value, List<Integer> offsets) { }

    private static String normalize(String value) { return SPACE.matcher(value).replaceAll(" ").trim(); }
    private static String decode(String value) {
        // encodeURIComponent uses percent escapes, never form-style '+'.
        if (value.contains("+") || value.contains("\r")) throw invalid();
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Revision no valida. Vuelve a analizar el documento.");
    }
}
