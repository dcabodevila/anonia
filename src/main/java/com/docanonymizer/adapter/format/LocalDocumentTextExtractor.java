package com.docanonymizer.adapter.format;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Routes a local document to the extractor of the registered format that recognises it. */
public final class LocalDocumentTextExtractor implements TextExtractorPort {

    private final DocumentFormatRegistry registry;

    public LocalDocumentTextExtractor() {
        this(DocumentFormatRegistry.standard());
    }

    public LocalDocumentTextExtractor(DocumentFormatRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new ExtractionException("La ruta no es un fichero legible.");
        }
        return registry.require(DocumentProbe.of(source)).extractor().extract(source);
    }
}
