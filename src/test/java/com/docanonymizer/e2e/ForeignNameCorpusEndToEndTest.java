package com.docanonymizer.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    private static String matchingText;

    @BeforeAll
    static void runPipeline() throws IOException {
        Path pdf = workDir.resolve("foreign-names.pdf");
        ForeignNameCorpusGenerator.generate(pdf);
        // Se usa el mismo extractor que compone el pipeline, sin plegar Unicode.
        extracted = new LocalDocumentTextExtractor().extract(pdf).rawText();
        var pipeline = PipelineFactory.standard();
        var analysis = pipeline.analyze(pdf);
        matchingText = analysis.normalizedText();
        result = pipeline.complete(analysis, analysis.candidates());
    }

    @Test
    void unicodeSentencesRoundTripExactly() {
        assertAll(ForeignNameCorpusGenerator.GROUPS.stream()
                .flatMap(group -> group.cases().stream())
                .map(seed -> () -> assertTrue(extracted.contains(seed.sentence()),
                        () -> "texto extraido distinto: " + seed.sentence())));
        assertAll(ForeignNameCorpusGenerator.T8.stream().flatMap(person -> person.mentions().stream())
                .map(mention -> () -> assertTrue(extracted.contains(mention.sentence()), mention.sentence())));
        assertAll(ForeignNameCorpusGenerator.T9.cases().stream()
                .map(mention -> () -> assertTrue(extracted.contains(mention.sentence()), mention.sentence())));
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

    @ParameterizedTest(name = "T4: {0}")
    @MethodSource("t4")
    void foreignGivenNames(NameCase seed) {
        assertRedacted(seed);
    }

    @ParameterizedTest(name = "T5: {0}")
    @MethodSource("t5")
    void knownSurnames(NameCase seed) {
        assertRedacted(seed);
    }

    @ParameterizedTest(name = "T6: {0}")
    @MethodSource("t6")
    void ambiguousNamesAreRedacted(NameCase seed) {
        assertRedacted(seed);
    }

    @Test
    void t8EveryMentionUsesItsSeedPlaceholderAndPreservesProse() {
        var olderWords = Stream.concat(ForeignNameCorpusGenerator.GROUPS.stream(),
                        Stream.of(ForeignNameCorpusGenerator.T9))
                .flatMap(group -> group.cases().stream())
                .flatMap(seed -> Stream.of(seed.name().split(" ")))
                .map(TextFolding::fold).collect(java.util.stream.Collectors.toSet());
        ForeignNameCorpusGenerator.T8.stream().flatMap(person -> Stream.of(person.seed().split(" ")))
                .forEach(word -> assertFalse(olderWords.contains(TextFolding.fold(word)), word));
        var labels = new java.util.HashSet<String>();
        for (var person : ForeignNameCorpusGenerator.T8) {
            var seed = result.accepted().stream()
                    .filter(d -> d.value().equals(person.seed())).findFirst().orElseThrow();
            String label = result.pseudonyms().get(seed.entityKey());
            assertTrue(labels.add(label), "personas distintas necesitan etiquetas distintas");
            for (var mention : person.mentions()) {
                String expected = mention.sentence();
                for (String word : Stream.concat(Stream.of(person.seed()),
                        Stream.of(person.seed().split(" "))).toList()) {
                    var hits = TextFolding.findWholeWordOccurrences(expected, word);
                    for (int i = hits.size() - 1; i >= 0; i--) {
                        int[] hit = hits.get(i);
                        expected = expected.substring(0, hit[0]) + label + expected.substring(hit[1]);
                    }
                }
                assertTrue(result.markdown().contains(expected), expected + "\n" + result.markdown());
            }
        }
        for (String control : ForeignNameCorpusGenerator.T8_CONTROLS) {
            assertTrue(result.markdown().contains(control), control);
        }
    }

    @Test
    void t9SharedSurnameUsesFirstSeedPlaceholderAndPreservesEverySentence() {
        var otherWords = Stream.concat(
                        ForeignNameCorpusGenerator.GROUPS.stream()
                                .flatMap(group -> group.cases().stream()).map(NameCase::name),
                        ForeignNameCorpusGenerator.T8.stream().map(person -> person.seed()))
                .flatMap(name -> Stream.of(name.split(" ")))
                .map(TextFolding::fold).collect(java.util.stream.Collectors.toSet());
        // El apellido compartido solo es intencional dentro de T9.
        var words = java.util.List.of("Andrés", "Morales", "Prieto", "Carmen", "Iglesias");
        for (String word : words) {
            assertFalse(otherWords.contains(TextFolding.fold(word)), word);
            assertTrue(TextFolding.findWholeWordOccurrences(result.markdown(), word).isEmpty(),
                    "nombre presente: " + word);
        }
        var seeds = ForeignNameCorpusGenerator.T9.cases();
        var first = result.accepted().stream()
                .filter(d -> d.value().equals(seeds.get(0).name())).findFirst().orElseThrow();
        var second = result.accepted().stream()
                .filter(d -> d.value().equals(seeds.get(1).name())).findFirst().orElseThrow();
        String andres = result.pseudonyms().get(first.entityKey());
        String carmen = result.pseudonyms().get(second.entityKey());
        assertTrue(andres != null && carmen != null, "faltan etiquetas de las personas");
        assertNotEquals(andres, carmen, "personas distintas necesitan etiquetas distintas");
        var expected = java.util.List.of(
                "Comparece D. " + andres + " para ratificar el escrito.",
                "Comparece Dña. " + carmen + " para aportar prueba.",
                "El testigo " + andres + " aportó el contrato.",
                andres + " firmó el acta y " + carmen + " la recibió.",
                andres + " e " + carmen + " comparecieron juntos.");
        assertAll(expected.stream().map(sentence -> () ->
                assertTrue(result.markdown().contains(sentence), sentence + "\n" + result.markdown())));
    }

    @Test
    void jurisdictionProseSurvivesVerbatim() {
        assertTrue(result.markdown().contains("tiene competencia para conocer del asunto"),
                "se perdio la prosa del control negativo");
    }

    @ParameterizedTest(name = "Control: {0}")
    @MethodSource("controls")
    void legitimateContentSurvives(String control) {
        assertTrue(TextFolding.fold(result.markdown()).contains(TextFolding.fold(control)),
                () -> "se perdio contenido legitimo: " + control);
    }

    @ParameterizedTest(name = "Non-person control: {0}")
    @MethodSource("nonPersonControls")
    void nonPersonControlsHaveNoPersonOverlap(String control) {
        int start = matchingText.indexOf(control);
        assertTrue(start >= 0, control);
        int end = start + control.length();
        var overlapping = result.accepted().stream()
                .filter(d -> d.start() < end && start < d.end()).toList();
        for (var detector : PipelineFactory.defaultDetectors()) {
            var hits = detector.detect(matchingText).stream()
                    .filter(d -> d.start() < end && start < d.end()).toList();
            assertTrue(hits.stream().noneMatch(d -> d.type() == DetectionType.PERSON),
                    () -> control + " detector=" + detector.name() + " hits=" + hits);
        }
        assertTrue(overlapping.stream().noneMatch(d -> d.type() == DetectionType.PERSON),
                () -> control + ": " + overlapping);
    }

    private static void assertRedacted(NameCase seed) {
        int nameStart = seed.sentence().indexOf(seed.name());
        String before = seed.sentence().substring(0, nameStart);
        String after = seed.sentence().substring(nameStart + seed.name().length());
        String sentencePattern = java.util.regex.Pattern.quote(before)
                + "\\[PERSONA_\\d+\\]" + java.util.regex.Pattern.quote(after);
        assertAll(
                () -> assertTrue(java.util.regex.Pattern.compile(sentencePattern)
                                .matcher(result.markdown()).find(),
                        () -> "frase alterada fuera del nombre: " + seed.sentence()
                                + "\n" + result.markdown()),
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
    static Stream<String> nonPersonControls() { return ForeignNameCorpusGenerator.NON_PERSON_CONTROLS.stream(); }
}
