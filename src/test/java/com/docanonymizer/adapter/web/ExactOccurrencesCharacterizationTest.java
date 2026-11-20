package com.docanonymizer.adapter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import com.docanonymizer.tools.ForeignNameCorpusGenerator;
import com.docanonymizer.tools.SampleDocumentGenerator;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the exact review candidates the web adapter offers and substitutes: every id, type,
 * range and entity key produced by {@link ExactOccurrences#expand} on analyze, and by
 * {@link ReviewEdits#replay} on apply (without edits and with reject/type edits), over the
 * security corpus, the foreign-name corpus and two synthetic HR workbooks.
 *
 * <p>The golden digests were recorded from the implementation before the performance rewrite;
 * a performance change must reproduce them exactly. To re-record intentionally run with
 * {@code -Dcharacterization.record=true} and justify the diff in the commit.
 */
class ExactOccurrencesCharacterizationTest {
    private static final Path GOLDEN = Path.of("src", "test", "resources", "characterization",
            "exact-occurrences.txt");

    @TempDir
    static Path workDir;

    private static final Map<String, Path> INPUTS = new LinkedHashMap<>();

    @BeforeAll
    static void createInputs() throws Exception {
        Path security = workDir.resolve("security.pdf");
        SampleDocumentGenerator.generate(security);
        INPUTS.put("security-corpus", security);
        Path foreign = workDir.resolve("foreign.pdf");
        ForeignNameCorpusGenerator.generate(foreign);
        INPUTS.put("foreign-name-corpus", foreign);
        generateHrWorkbooks(workDir, 50, 150, 600);
        INPUTS.put("hr-50", workDir.resolve("hr-50.xlsx"));
        INPUTS.put("hr-150", workDir.resolve("hr-150.xlsx"));
        INPUTS.put("hr-600", workDir.resolve("hr-600.xlsx"));
    }

    /** Runs the committed synthetic generator with the test class path (Apache POI included). */
    private static void generateHrWorkbooks(Path dir, int... sizes) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                Path.of("e2e", "perf", "HrWorkbookGenerator.java").toString(), dir.toString()));
        for (int size : sizes) command.add(String.valueOf(size));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "HR workbook generator timed out");
        assertEquals(0, process.exitValue(), "HR workbook generator failed");
    }

    @Test
    void expandAndReplayReproduceTheRecordedCandidates() throws Exception {
        Map<String, String> actual = new LinkedHashMap<>();
        for (var input : INPUTS.entrySet()) {
            // A fresh pipeline per input keeps detector id sequences deterministic.
            AnonymizationPipeline pipeline = PipelineFactory.standard();
            AnonymizationPipeline.Analysis analysis = pipeline.analyze(input.getValue());
            AnonymizationPipeline.Analysis expanded = ExactOccurrences.expand(analysis);
            actual.put(input.getKey() + " expand", digest(expanded.candidates()));
            AnonymizationPipeline.ReviewedSelection reviewed = ReviewEdits.replay(expanded, "review-v1\n");
            actual.put(input.getKey() + " replay", digest(reviewed));
            actual.put(input.getKey() + " complete", digest(pipeline.complete(expanded, reviewed)));
            actual.put(input.getKey() + " replay-edited",
                    digest(ReviewEdits.replay(expanded, editedReview(expanded.candidates()))));
        }
        String rendered = render(actual);
        if (Boolean.getBoolean("characterization.record")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, rendered, StandardCharsets.UTF_8);
        }
        // Line endings depend on the checkout (core.autocrlf); the digests do not.
        assertEquals(Files.readString(GOLDEN, StandardCharsets.UTF_8).replace("\r\n", "\n"), rendered);
    }

    /**
     * The web analyze and apply steps must not scale with candidates times text length: before
     * the rewrite the 150-employee workbook needed about 3 s to expand and 5 s to replay.
     */
    @Test
    void expandAndReplayOfTheHrWorkbookStayWithinOneSecond() throws Exception {
        AnonymizationPipeline.Analysis analysis = PipelineFactory.standard().analyze(INPUTS.get("hr-150"));
        long started = System.nanoTime();
        AnonymizationPipeline.Analysis expanded = ExactOccurrences.expand(analysis);
        ReviewEdits.replay(expanded, "review-v1\n");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 1_000, "expand + replay took " + elapsedMs + " ms");
    }

    /** Rejects the first entity and retypes the second one, through the browser edit format. */
    private static String editedReview(List<Detection> candidates) {
        List<String> keys = candidates.stream().map(Detection::entityKey).distinct().toList();
        Detection first = candidates.stream().filter(d -> d.entityKey().equals(keys.get(0))).findFirst().orElseThrow();
        Detection second = candidates.stream().filter(d -> d.entityKey().equals(keys.get(1))).findFirst().orElseThrow();
        return "review-v1\n" + line("reject", first.id(), "true") + "\n" + line("type", second.id(), "TEXTO");
    }

    private static String line(String action, String id, String value) {
        return action + "\t" + encode(id) + "\t" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Final Markdown without its timestamp line, plus every verifier finding. */
    private static String digest(AnonymizationResult result) throws Exception {
        String markdown = result.markdown().lines().filter(line -> !line.contains("T") || !line.matches(".*\\d{4}-\\d{2}-\\d{2}T.*"))
                .collect(java.util.stream.Collectors.joining("\n"));
        String findings = result.verification().findings().stream().map(Object::toString)
                .collect(java.util.stream.Collectors.joining("\n"));
        return "passed=" + result.verification().passed() + " markdown=" + sha256(markdown)
                + " findings=" + result.verification().findings().size() + ":" + sha256(findings);
    }

    /**
     * Propagation, overlap arbitration and verification must not grow with people times text:
     * before the rewrite 600 employees needed about 13 s to analyze and 13 s to verify.
     */
    @Test
    void analyzeAndVerifyOfSixHundredEmployeesStayWithinBudget() throws Exception {
        AnonymizationPipeline pipeline = PipelineFactory.standard();
        long started = System.nanoTime();
        AnonymizationPipeline.Analysis analysis = pipeline.analyze(INPUTS.get("hr-600"));
        long analyzeMs = (System.nanoTime() - started) / 1_000_000;
        started = System.nanoTime();
        AnonymizationResult result = pipeline.complete(analysis, analysis.candidates());
        long completeMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(result.verification().passed(), result.verification().toString());
        assertTrue(analyzeMs < 5_000, "analyze took " + analyzeMs + " ms");
        assertTrue(completeMs < 5_000, "substitution and verification took " + completeMs + " ms");
    }

    private static String digest(AnonymizationPipeline.ReviewedSelection selection) throws Exception {
        return digest(selection.effective()) + " rejected=" + selection.rejectedIds().size() + ":"
                + sha256(String.join("\n", new TreeSet<>(selection.rejectedIds())));
    }

    private static String digest(List<Detection> detections) throws Exception {
        StringBuilder canonical = new StringBuilder();
        for (Detection d : detections) {
            canonical.append(d.id()).append('|').append(d.type().label()).append('|').append(d.start())
                    .append('|').append(d.end()).append('|').append(d.value()).append('|')
                    .append(d.entityKey()).append('|').append(d.provenance()).append('|')
                    .append(d.confidence()).append('\n');
        }
        return detections.size() + ":" + sha256(canonical.toString());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String render(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> out.append(key).append(' ').append(value).append('\n'));
        return out.toString();
    }
}
