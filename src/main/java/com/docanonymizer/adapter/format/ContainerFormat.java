package com.docanonymizer.adapter.format;

import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.util.List;

/**
 * A format stored inside a generic container (ZIP for OOXML, OLE2 for legacy Office),
 * recognised by the container signature plus an entry only that format contains.
 */
public record ContainerFormat(
        String displayName,
        List<String> extensions,
        List<String> mimeTypes,
        TextExtractorPort extractor,
        Container container,
        String requiredEntry) implements DocumentFormat {

    public enum Container {
        ZIP,
        OLE2
    }

    public ContainerFormat {
        extensions = List.copyOf(extensions);
        mimeTypes = List.copyOf(mimeTypes);
    }

    @Override
    public boolean matches(DocumentProbe probe) throws IOException {
        return switch (container) {
            case ZIP -> probe.startsWith('P', 'K', 0x03, 0x04) && probe.containsZipEntry(requiredEntry);
            case OLE2 -> probe.startsWith(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)
                    && probe.containsOle2Entry(requiredEntry);
        };
    }
}
