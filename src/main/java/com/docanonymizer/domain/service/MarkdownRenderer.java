package com.docanonymizer.domain.service;

import java.time.Instant;
import java.util.Map;

/** Preserves extracted lines exactly after selected spans have been replaced. */
public final class MarkdownRenderer {
    public record Metadata(
            String sourceSha256,
            int pageCount,
            Map<String, Long> countsByType,
            String toolVersion,
            Instant generatedAt) {
    }

    public String render(String anonymizedText, Metadata metadata) {
        return anonymizedText;
    }
}
