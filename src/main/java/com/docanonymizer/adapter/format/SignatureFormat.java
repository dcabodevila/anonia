package com.docanonymizer.adapter.format;

import com.docanonymizer.domain.port.TextExtractorPort;
import java.util.List;

/** A format recognised by a fixed leading byte signature, whatever its declared extension. */
public record SignatureFormat(
        String displayName,
        List<String> extensions,
        List<String> mimeTypes,
        TextExtractorPort extractor,
        int[] signature) implements DocumentFormat {

    public SignatureFormat {
        extensions = List.copyOf(extensions);
        mimeTypes = List.copyOf(mimeTypes);
        signature = signature.clone();
    }

    @Override
    public boolean matches(DocumentProbe probe) {
        return probe.startsWith(signature);
    }
}
