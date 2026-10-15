package com.docanonymizer.adapter.format;

import com.docanonymizer.domain.port.TextExtractorPort;
import java.util.List;

/**
 * A text format without a binary signature: recognised by its declared extension, provided
 * the content does not look binary. Register it after every signature-based format.
 */
public record TextFormat(
        String displayName,
        List<String> extensions,
        List<String> mimeTypes,
        TextExtractorPort extractor) implements DocumentFormat {

    public TextFormat {
        extensions = List.copyOf(extensions);
        mimeTypes = List.copyOf(mimeTypes);
    }

    @Override
    public boolean matches(DocumentProbe probe) {
        return probe.hasExtension(extensions) && !probe.looksBinary();
    }
}
