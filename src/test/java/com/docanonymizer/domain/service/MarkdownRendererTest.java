package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkdownRendererTest {
    @Test
    void doesNotInventAFinalLineBreak() {
        String source = "Titulo\n\nUltima linea";
        var metadata = new MarkdownRenderer.Metadata("hash", 1, Map.of(), "test", Instant.EPOCH);
        assertEquals(source, new MarkdownRenderer().render(source, metadata));
    }

    @Test
    void preservesExtractedLinesBlankLinesAndHeadingLikeText() {
        String source = "TITULO\nPrimera linea\nSegunda [PERSONA_001]\n\n\nFINAL\n";
        var metadata = new MarkdownRenderer.Metadata("hash", 1, Map.of(), "test", Instant.EPOCH);
        assertEquals(source, new MarkdownRenderer().render(source, metadata));
    }
}
