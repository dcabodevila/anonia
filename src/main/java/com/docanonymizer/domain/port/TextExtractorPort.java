package com.docanonymizer.domain.port;

import com.docanonymizer.domain.model.ExtractedDocument;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Obtiene el texto de un documento. El dominio no sabe que existe PDFBox.
 * Cambiar de extractor (o anadir OCR mas adelante) es implementar esta interfaz.
 */
public interface TextExtractorPort {

    ExtractedDocument extract(Path source) throws IOException;

    /** Excepcion de extraccion: el mensaje NUNCA debe incluir contenido del documento. */
    class ExtractionException extends RuntimeException {
        public ExtractionException(String message) {
            super(message);
        }

        public ExtractionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
