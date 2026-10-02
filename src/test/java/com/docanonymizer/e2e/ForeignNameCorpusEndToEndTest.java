package com.docanonymizer.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.adapter.ocr.LocalDocumentTextExtractor;
import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.service.CanonicalForm;
import com.docanonymizer.domain.service.TextFolding;
import com.docanonymizer.tools.ForeignNameCorpusGenerator;
import com.docanonymizer.tools.ForeignNameCorpusGenerator.NameCase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Corpus Unicode compartido: PDF sembrado -> pipeline completo -> Markdown. */
@DisplayName("Corpus de nombres extranjeros")
class ForeignNameCorpusEndToEndTest {

    @TempDir
    static Path workDir;

    private static AnonymizationResult result;
    private static String extracted;

    @BeforeAll
    static void runPipeline() throws IOException {
        Path pdf = workDir.resolve("foreign-names.pdf");
        ForeignNameCorpusGenerator.generate(pdf);
        // Se usa el mismo extractor que compone el pipeline, sin plegar Unicode.
        extracted = new LocalDocumentTextExtractor().extract(pdf).rawText();
        result = PipelineFactory.standard().run(pdf);
    }

    @Test
    void unicodeSentencesRoundTripExactly() {
        assertAll(ForeignNameCorpusGenerator.GROUPS.stream()
                .flatMap(group -> group.cases().stream())
                .map(seed -> () -> assertTrue(extracted.contains(seed.sentence()),
                        () -> "texto extraido distinto: " + seed.sentence())));
        assertAll(ForeignNameCorpusGenerator.CONTROL_SENTENCES.stream()
                .map(sentence -> () -> assertTrue(extracted.contains(sentence),
                        () -> "control no extraido: " + sentence)));
    }

    @ParameterizedTest(name = "T1: {0}")
    @MethodSource("t1")
    void unicodeNamesAfterCue(NameCase seed) {
        assertRedacted(seed);
    }

    @ParameterizedTest(name = "T3: {0}")
    @MethodSource("t3")
    void honorificCues(NameCase seed) {
        assertRedacted(seed);
    }

    @Disabled("T4: foreign given-name dictionary pending")
    @ParameterizedTest(name = "T4: {0}")
    @MethodSource("t4")
    void foreignGivenNames(NameCase seed) {
        assertRedacted(seed);
    }

    @Disabled("T5: surname dictionary pending")
    @ParameterizedTest(name = "T5: {0}")
    @MethodSource("t5")
    void knownSurnames(NameCase seed) {
        assertRedacted(seed);
    }

    @Disabled("T6: ambiguous-name confidence pending")
    @ParameterizedTest(name = "T6: {0}")
    @MethodSource("t6")
    void ambiguousNamesHaveLowerConfidence(NameCase seed) {
        assertAll(
                () -> assertRedacted(seed),
                () -> {
                    var detections = result.accepted().stream()
                            .filter(d -> d.type() == DetectionType.PERSON)
                            .filter(d -> CanonicalForm.forCompare(d.value())
                                    .contains(CanonicalForm.forCompare(seed.name())))
                            .toList();
                    assertFalse(detections.isEmpty(), "falta deteccion PERSON: " + seed.name());
                    assertTrue(detections.stream().allMatch(d -> d.confidence() < 0.85),
                            "confianza debe ser inferior a 0.85: " + seed.name());
                });
    }

    @ParameterizedTest(name = "Control: {0}")
    @MethodSource("controls")
    void legitimateContentSurvives(String control) {
        assertTrue(TextFolding.fold(result.markdown()).contains(TextFolding.fold(control)),
                () -> "se perdio contenido legitimo: " + control);
    }

    private static void assertRedacted(NameCase seed) {
        assertAll(
                () -> assertFalse(TextFolding.fold(result.markdown())
                                .contains(TextFolding.fold(seed.name())),
                        () -> "nombre sembrado presente: " + seed.name()),
                () -> assertFalse(CanonicalForm.forCompare(result.markdown())
                                .contains(CanonicalForm.forCompare(seed.name())),
                        () -> "nombre presente con otro formato: " + seed.name()));
    }

    private static Stream<NameCase> cases(String taskId) {
        return ForeignNameCorpusGenerator.GROUPS.stream()
                .filter(group -> group.taskId().equals(taskId))
                .flatMap(group -> group.cases().stream());
    }

    static Stream<NameCase> t1() { return cases("T1"); }
    static Stream<NameCase> t3() { return cases("T3"); }
    static Stream<NameCase> t4() { return cases("T4"); }
    static Stream<NameCase> t5() { return cases("T5"); }
    static Stream<NameCase> t6() { return cases("T6"); }
    static Stream<String> controls() { return ForeignNameCorpusGenerator.NEGATIVE_CONTROLS.stream(); }
}
