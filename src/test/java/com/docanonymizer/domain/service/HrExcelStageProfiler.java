package com.docanonymizer.domain.service;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.adapter.format.DocumentFormatRegistry;
import com.docanonymizer.adapter.format.LocalDocumentTextExtractor;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.VerificationReport;
import com.docanonymizer.domain.port.DetectorPort;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Times every pipeline stage, one by one, for the synthetic HR workbooks written by
 * {@code e2e/perf/HrWorkbookGenerator.java}. It mirrors {@link AnonymizationPipeline#analyze}
 * and {@link AnonymizationPipeline#complete} step by step, plus the web-only steps
 * ({@code ExactOccurrences.expand} on analyze and {@code ReviewEdits.replay} on apply), so the
 * slow stage can be named. It is a measurement tool, not a unit test (Surefire skips it).
 *
 * <pre>
 * mvn -o -DskipTests package &amp;&amp; mvn -o test-compile
 * java -cp "target/doc-anonymizer.jar;target/test-classes" \
 *     com.docanonymizer.domain.service.HrExcelStageProfiler e2e/.runtime/hr-fixtures [out.json]
 * </pre>
 *
 * <p>Add {@code -Dhr.sizes=50,150,300} before {@code -cp} to profile only those workbooks.
 *
 * <p>Use {@code :} instead of {@code ;} as class path separator outside Windows. Per stage it
 * reports wall time and bytes allocated by the calling thread.
 */
public final class HrExcelStageProfiler {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private final Map<String, long[]> stages = new LinkedHashMap<>();

    private <T> T time(String stage, Callable<T> work) throws Exception {
        long allocated = THREADS.getCurrentThreadAllocatedBytes();
        long started = System.nanoTime();
        T value = work.call();
        long elapsed = System.nanoTime() - started;
        long bytes = THREADS.getCurrentThreadAllocatedBytes() - allocated;
        long[] previous = stages.getOrDefault(stage, new long[2]);
        stages.put(stage, new long[] {previous[0] + elapsed, previous[1] + bytes});
        return value;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "e2e/.runtime/hr-fixtures");
        // Optional -Dhr.sizes=50,300 limits the run: the largest workbooks take minutes per stage.
        List<String> only = List.of(System.getProperty("hr.sizes", "").split(",")).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString().matches("hr-\\d+\\.xlsx"))
                    .filter(p -> only.isEmpty() || only.contains(String.valueOf(rows(p))))
                    .sorted((a, b) -> Integer.compare(rows(a), rows(b))).toList();
        }
        if (files.isEmpty()) throw new IllegalStateException("No hr-<rows>.xlsx files in " + dir);
        // A requested size without its workbook must fail, not silently shrink the sweep.
        List<String> missing = only.stream().filter(size -> files.stream()
                .noneMatch(p -> String.valueOf(rows(p)).equals(size))).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing hr-<rows>.xlsx in " + dir + " for sizes " + missing);
        }
        // Warm up the JIT and the gazetteer with the smallest file so the first size is fair.
        new HrExcelStageProfiler().profile(files.get(0));

        List<Map<String, Object>> results = new ArrayList<>();
        for (Path file : files) {
            System.gc();
            HrExcelStageProfiler profiler = new HrExcelStageProfiler();
            Map<String, Object> result = profiler.profile(file);
            results.add(result);
            print(result);
        }
        if (args.length > 1) {
            Files.writeString(Path.of(args[1]), json(results), StandardCharsets.UTF_8);
        }
    }

    static int rows(Path file) {
        return Integer.parseInt(file.getFileName().toString().replaceAll("\\D", ""));
    }

    Map<String, Object> profile(Path file) throws Exception {
        LocalDocumentTextExtractor extractor =
                new LocalDocumentTextExtractor(DocumentFormatRegistry.standard());
        List<DetectorPort> detectors = PipelineFactory.defaultDetectors();
        DetectionEngine engine = new DetectionEngine(detectors);
        long total = System.nanoTime();

        ExtractedDocument extracted = time("1 extraction", () -> extractor.extract(file));
        ExtractedDocument cleaned = time("2 header/footer strip",
                () -> new HeaderFooterStripper().strip(extracted).document());
        String source = cleaned.rawText();
        TextNormalizer.MappedText matching = time("3 normalization",
                () -> new TextNormalizer().normalizeMapped(source));
        String normalized = matching.text();

        Map<String, Long> perDetector = new LinkedHashMap<>();
        List<Detection> raw = new ArrayList<>();
        for (DetectorPort detector : detectors) {
            long started = System.nanoTime();
            List<Detection> found = time("4a detection: detectors", () -> detector.detect(normalized));
            perDetector.merge(detector.name(), (System.nanoTime() - started) / 1_000_000, Long::sum);
            raw.addAll(found);
        }
        List<Detection> seeds = time("4b detection: resolveOverlaps", () -> engine.resolveOverlaps(raw));
        List<Detection> propagated = time("5a propagation",
                () -> new EntityPropagator().propagate(normalized, seeds));
        List<Detection> merged = time("5b propagation: resolveOverlaps", () -> {
            List<Detection> all = new ArrayList<>(seeds);
            all.addAll(propagated);
            return engine.resolveOverlaps(all);
        });
        List<Detection> unified = time("5c unification (PersonEntityResolver)",
                () -> new PersonEntityResolver().resolve(merged));
        List<Detection> candidates = time("5d offset mapping + resolveOverlaps", () -> {
            List<Detection> mapped = new ArrayList<>();
            for (Detection d : unified) {
                int start = matching.start(d.start());
                int end = matching.end(d.end());
                mapped.add(new Detection(d.id(), d.type(), start, end, source.substring(start, end),
                        d.entityKey(), d.provenance(), d.confidence()));
            }
            return ExcludedEntities.empty().filter(engine.resolveOverlaps(mapped));
        });
        AnonymizationPipeline.Analysis analysis = new AnonymizationPipeline.Analysis(
                source, candidates, extracted.sourceSha256(), extracted.pageCount());
        AnonymizationPipeline.Analysis expanded = time("6 web: ExactOccurrences.expand",
                () -> (AnonymizationPipeline.Analysis) webMethod("ExactOccurrences", "expand",
                        AnonymizationPipeline.Analysis.class).invoke(null, analysis));
        AnonymizationPipeline.ReviewedSelection reviewed = time("7 web apply: ReviewEdits.replay",
                () -> (AnonymizationPipeline.ReviewedSelection) webMethod("ReviewEdits", "replay",
                        AnonymizationPipeline.Analysis.class, String.class)
                        .invoke(null, expanded, "review-v1\n"));
        List<Detection> accepted = reviewed.effective();
        Map<String, String> pseudonyms = time("8a pseudonyms", () -> new PseudonymAssigner().assign(accepted));
        String substituted = time("8b substitution",
                () -> new TextAnonymizer().apply(expanded.normalizedText(), accepted, pseudonyms));
        String markdown = time("8c markdown render", () -> new MarkdownRenderer().render(substituted,
                new MarkdownRenderer.Metadata(expanded.sourceSha256(), expanded.pageCount(),
                        accepted.stream().collect(Collectors.groupingBy(d -> d.type().label(),
                                LinkedHashMap::new, Collectors.counting())),
                        AnonymizationPipeline.TOOL_VERSION, Instant.now())));
        VerificationReport report = time("9 verification", () -> new RedactionVerifier(
                new RunScopedIdentifier()).verify(markdown, accepted, pseudonyms,
                expanded.candidates(), reviewed.rejectedIds(), expanded.normalizedText()));
        long totalMs = (System.nanoTime() - total) / 1_000_000;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", file.getFileName().toString());
        result.put("rows", rows(file));
        result.put("textChars", source.length());
        result.put("rawDetections", raw.size());
        result.put("seeds", seeds.size());
        result.put("propagated", propagated.size());
        result.put("candidates", candidates.size());
        result.put("expandedCandidates", expanded.candidates().size());
        result.put("entities", expanded.candidates().stream().map(Detection::entityKey).distinct().count());
        result.put("verificationPassed", report.passed());
        result.put("totalMs", totalMs);
        Map<String, Object> stageMs = new LinkedHashMap<>();
        Map<String, Object> stageMb = new LinkedHashMap<>();
        stages.forEach((stage, value) -> {
            stageMs.put(stage, value[0] / 1_000_000);
            stageMb.put(stage, value[1] / (1024 * 1024));
        });
        result.put("stageMs", stageMs);
        result.put("stageAllocatedMb", stageMb);
        result.put("detectorMs", perDetector);
        result.put("heapUsedMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                / (1024 * 1024));
        return result;
    }

    private static Method webMethod(String type, String name, Class<?>... parameters) throws Exception {
        Method method = Class.forName("com.docanonymizer.adapter.web." + type)
                .getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    private static void print(Map<String, Object> result) {
        System.out.printf("%n== %s: %s chars, %s raw -> %s seeds, %s propagated, %s candidates, "
                + "%s after expand, %s entities, total %s ms, verification %s%n",
                result.get("file"), result.get("textChars"), result.get("rawDetections"),
                result.get("seeds"), result.get("propagated"), result.get("candidates"),
                result.get("expandedCandidates"), result.get("entities"), result.get("totalMs"),
                result.get("verificationPassed"));
        Map<String, Object> mb = (Map<String, Object>) result.get("stageAllocatedMb");
        ((Map<String, Object>) result.get("stageMs")).forEach((stage, ms) ->
                System.out.printf("  %-42s %8s ms %8s MB allocated%n", stage, ms, mb.get(stage)));
        ((Map<String, Object>) result.get("detectorMs")).forEach((name, ms) ->
                System.out.printf("    detector %-32s %8s ms%n", name, ms));
    }

    @SuppressWarnings("unchecked")
    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + json(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(HrExcelStageProfiler::json).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof String text) return "\"" + text.replace("\"", "\\\"") + "\"";
        return String.valueOf(value);
    }
}
