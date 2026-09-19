package com.docanonymizer.adapter.ocr;

import com.docanonymizer.adapter.pdf.PdfBoxTextExtractor;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Routes trusted document signatures to their local text extraction implementation. */
public final class LocalDocumentTextExtractor implements TextExtractorPort {

    private final TextExtractorPort pdfExtractor;
    private final TextExtractorPort imageExtractor;

    public LocalDocumentTextExtractor() {
        this(new PdfBoxTextExtractor(), new TesseractImageTextExtractor());
    }

    LocalDocumentTextExtractor(TextExtractorPort pdfExtractor, TextExtractorPort imageExtractor) {
        this.pdfExtractor = pdfExtractor;
        this.imageExtractor = imageExtractor;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        if (!java.nio.file.Files.isRegularFile(source)) {
            throw new ExtractionException("La ruta no es un fichero legible.");
        }
        return switch (detect(source)) {
            case PDF -> pdfExtractor.extract(source);
            case JPEG, PNG -> imageExtractor.extract(source);
            case UNKNOWN -> throw new ExtractionException(
                    "Solo se admiten documentos PDF y fotos JPEG o PNG.");
        };
    }

    private SourceType detect(Path source) throws IOException {
        byte[] header = new byte[8];
        try (InputStream input = java.nio.file.Files.newInputStream(source)) {
            int read = input.read(header);
            if (read < 3) {
                return SourceType.UNKNOWN;
            }
        }
        if (header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                && header[3] == 'F' && header[4] == '-') {
            return SourceType.PDF;
        }
        if ((header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) {
            return SourceType.JPEG;
        }
        if ((header[0] & 0xff) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0d && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a) {
            return SourceType.PNG;
        }
        return SourceType.UNKNOWN;
    }

    private enum SourceType {
        PDF,
        JPEG,
        PNG,
        UNKNOWN
    }
}
