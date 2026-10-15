package com.docanonymizer.adapter.format;

import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.util.List;

/**
 * One importable document format. Adding a format means implementing this contract (or
 * reusing {@link SignatureFormat}) and registering it in {@link DocumentFormatRegistry#standard()}.
 */
public interface DocumentFormat {

    /** Human-readable name used in user-facing messages, e.g. "PDF". */
    String displayName();

    /** Lower-case extensions including the dot; the first one is the canonical one. */
    List<String> extensions();

    /** MIME types offered to the browser file picker. */
    List<String> mimeTypes();

    /**
     * Recognises the document. Binary formats must rely on the real content, never on the
     * declared extension alone.
     */
    boolean matches(DocumentProbe probe) throws IOException;

    TextExtractorPort extractor();
}
