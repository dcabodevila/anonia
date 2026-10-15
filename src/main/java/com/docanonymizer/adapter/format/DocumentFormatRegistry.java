package com.docanonymizer.adapter.format;

import com.docanonymizer.adapter.ocr.TesseractImageTextExtractor;
import com.docanonymizer.adapter.office.WordTextExtractor;
import com.docanonymizer.adapter.pdf.PdfBoxTextExtractor;
import com.docanonymizer.adapter.text.PlainTextExtractor;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The single place where importable formats are registered. Registration order is
 * resolution priority: content-recognised formats go before extension-recognised ones.
 */
public final class DocumentFormatRegistry {

    private final List<DocumentFormat> formats;

    public DocumentFormatRegistry(List<DocumentFormat> formats) {
        this.formats = List.copyOf(formats);
    }

    public static DocumentFormatRegistry standard() {
        TextExtractorPort images = new TesseractImageTextExtractor();
        TextExtractorPort words = new WordTextExtractor();
        return new DocumentFormatRegistry(List.of(
                new SignatureFormat("PDF", List.of(".pdf"), List.of("application/pdf"),
                        new PdfBoxTextExtractor(), new int[] {'%', 'P', 'D', 'F', '-'}),
                new SignatureFormat("JPEG", List.of(".jpg", ".jpeg"), List.of("image/jpeg"),
                        images, new int[] {0xff, 0xd8, 0xff}),
                new SignatureFormat("PNG", List.of(".png"), List.of("image/png"),
                        images, new int[] {0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}),
                new ContainerFormat("Word", List.of(".docx"),
                        List.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                        words, ContainerFormat.Container.ZIP, "word/document.xml"),
                new ContainerFormat("Word", List.of(".doc"), List.of("application/msword"),
                        words, ContainerFormat.Container.OLE2, "WordDocument"),
                // Extension-recognised formats last: any real signature above wins.
                new TextFormat("Markdown", List.of(".md", ".markdown"), List.of("text/markdown"),
                        new PlainTextExtractor()),
                new TextFormat("TXT", List.of(".txt"), List.of("text/plain"), new PlainTextExtractor())));
    }

    public List<DocumentFormat> formats() {
        return formats;
    }

    public Optional<DocumentFormat> resolve(DocumentProbe probe) throws IOException {
        for (DocumentFormat format : formats) {
            if (format.matches(probe)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** Resolves the format or fails with a message that never includes document content. */
    public DocumentFormat require(DocumentProbe probe) throws IOException {
        return resolve(probe).orElseThrow(
                () -> new TextExtractorPort.ExtractionException(unsupportedMessage()));
    }

    public String unsupportedMessage() {
        return "Formato no admitido. Formatos admitidos: " + formats.stream()
                .map(DocumentFormat::displayName)
                .distinct()
                .collect(Collectors.joining(", ")) + ".";
    }

    /** Value for the HTML file input {@code accept} attribute: MIME types, then extensions. */
    public String acceptAttribute() {
        Stream<String> mimeTypes = formats.stream().flatMap(format -> format.mimeTypes().stream());
        Stream<String> extensions = formats.stream().flatMap(format -> format.extensions().stream());
        return Stream.concat(mimeTypes, extensions).distinct().collect(Collectors.joining(","));
    }
}
