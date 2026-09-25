package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.model.PageText;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnonymizationPipelineTest {
    @Test
    void replacesSelectedEntityAcrossHyphenatedLineBreakInRetainedSource() throws Exception {
        String source = "Opening words provide enough ordinary text for reliable extraction.\nThe manifesta-\nciones were recorded in this document with more ordinary words.";
        AnonymizationPipeline pipeline = pipeline(source, (text, candidates) -> candidates);
        AnonymizationPipeline.Analysis analysis = pipeline.analyze(Path.of("unused.pdf"));
        assertEquals(source, analysis.normalizedText());
        assertEquals("manifesta-\nciones", analysis.candidates().get(0).value());
        assertEquals(source.indexOf("manifesta-"), analysis.candidates().get(0).start());
        assertTrue(pipeline.complete(analysis, analysis.candidates()).markdown().contains("The ["));
        assertTrue(!pipeline.complete(analysis, analysis.candidates()).markdown().contains("manifesta-"));
        String markdown = pipeline.complete(analysis, analysis.candidates()).markdown();
        assertEquals(source.chars().filter(c -> c == '\n').count(),
                markdown.chars().filter(c -> c == '\n').count());
        assertTrue(markdown.contains("The [CODIGO_001]\nciones were") == false);
        assertTrue(markdown.contains("The [CODIGO_001]\n were recorded"));
    }

    @Test
    void reviewCoordinatesAndRejectionRemainOnRetainedSource() throws Exception {
        String source = "Opening words provide enough ordinary text for reliable extraction.\nThe manifesta-\nciones were recorded and manifesta-\nciones appeared again with more ordinary words.";
        AnonymizationPipeline pipeline = pipeline(source, (text, candidates) -> List.of());
        AnonymizationPipeline.Analysis analysis = pipeline.analyze(Path.of("unused.pdf"));
        assertEquals(source, analysis.normalizedText());
        assertEquals(2, analysis.candidates().size());
        Detection second = analysis.candidates().get(1);
        assertEquals(source.indexOf("manifesta-", source.indexOf("manifesta-") + 1), second.start());
        assertEquals(source.substring(second.start(), second.end()), second.value());
        assertEquals(source, pipeline.complete(analysis, List.of()).markdown());
    }

    private static AnonymizationPipeline pipeline(String source, com.docanonymizer.domain.port.ReviewPort review) {
        return new AnonymizationPipeline(
                path -> new ExtractedDocument(List.of(new PageText(1, List.of(source.split("\\n", -1)))), "hash"),
                new DetectionEngine(List.of(new com.docanonymizer.domain.port.DetectorPort() {
                    public String name() { return "test"; }
                    public List<Detection> detect(String text) {
                    java.util.ArrayList<Detection> found = new java.util.ArrayList<>();
                    for (int at = text.indexOf("manifestaciones"); at >= 0;
                            at = text.indexOf("manifestaciones", at + 1)) {
                        found.add(new Detection("seed:" + at, DetectionType.CODIGO, at,
                                at + "manifestaciones".length(), "manifestaciones", "entity",
                                Provenance.REGEX, 1.0));
                    }
                    return found;
                    }
                })), review, new RunScopedIdentifier(), Clock.systemUTC());
    }

    @Test
    void retainsLeadingTrailingAndRepeatedBlankLinesInOutput() throws Exception {
        String source = "\n\nOpening section with enough ordinary words for reliable extraction.\n\n\n\nClosing section with additional ordinary words for reliable extraction.\n\n";
        AnonymizationPipeline pipeline = new AnonymizationPipeline(
                path -> new ExtractedDocument(List.of(new PageText(1, List.of(source.split("\n", -1)))), "hash"),
                new DetectionEngine(List.of()), (text, candidates) -> List.of(),
                new RunScopedIdentifier(), Clock.systemUTC());

        String markdown = pipeline.run(Path.of("unused.pdf")).markdown();
        assertEquals(source, markdown);
    }
}
