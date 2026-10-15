package com.docanonymizer.adapter.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentFormatRegistryTest {

    private static final byte[] PDF = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0x10};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0};

    @TempDir Path temp;

    @Test void standardRegistryRecognisesExistingFormatsBySignatureNotByExtension() throws Exception {
        DocumentFormatRegistry registry = DocumentFormatRegistry.standard();

        assertEquals("PDF", registry.require(DocumentProbe.of(PDF, "photo.png")).displayName());
        assertEquals("JPEG", registry.require(DocumentProbe.of(JPEG, "report.pdf")).displayName());
        assertEquals("PNG", registry.require(DocumentProbe.of(PNG, null)).displayName());
    }

    @Test void declaredExtensionAloneNeverMakesBinaryFormatsMatch() throws Exception {
        DocumentFormatRegistry registry = DocumentFormatRegistry.standard();

        assertTrue(registry.resolve(DocumentProbe.of("not a pdf".getBytes(StandardCharsets.UTF_8),
                "fake.pdf")).isEmpty());
    }

    @Test void unknownContentIsRejectedListingSupportedFormatsWithoutDocumentContent() {
        DocumentFormatRegistry registry = new DocumentFormatRegistry(List.of(
                fake("Alpha", ".a", "x/a", 'A'), fake("Beta", ".b", "x/b", 'B')));

        var error = assertThrows(TextExtractorPort.ExtractionException.class,
                () -> registry.require(DocumentProbe.of("Juan secret".getBytes(StandardCharsets.UTF_8), "x.exe")));

        assertEquals("Formato no admitido. Formatos admitidos: Alpha, Beta.", error.getMessage());
        assertFalse(error.getMessage().contains("Juan"));
    }

    @Test void registrationOrderIsResolutionPriority() throws Exception {
        DocumentFormat first = fake("First", ".a", "x/a", 'Z');
        DocumentFormat second = fake("Second", ".b", "x/b", 'Z');

        DocumentFormatRegistry registry = new DocumentFormatRegistry(List.of(first, second));

        assertSame(first, registry.require(DocumentProbe.of(new byte[] {'Z'}, null)));
    }

    @Test void acceptAttributeListsMimeTypesThenExtensionsInRegistrationOrder() {
        DocumentFormatRegistry registry = new DocumentFormatRegistry(List.of(
                new SignatureFormat("Alpha", List.of(".a", ".aa"), List.of("x/a"), null, new int[] {'A'}),
                fake("Beta", ".b", "x/b", 'B')));

        assertEquals("x/a,x/b,.a,.aa,.b", registry.acceptAttribute());
    }

    @Test void standardAcceptAttributeKeepsExistingPickerFormats() {
        assertTrue(DocumentFormatRegistry.standard().acceptAttribute()
                .startsWith("application/pdf,image/jpeg,image/png,"));
    }

    @Test void localExtractorRoutesToTheMatchingFormatsExtractor() throws Exception {
        Path source = Files.write(temp.resolve("upload.bin"), new byte[] {'B', 'x'});
        DocumentFormatRegistry registry = new DocumentFormatRegistry(List.of(
                fake("Alpha", ".a", "x/a", 'A'), fake("Beta", ".b", "x/b", 'B')));

        ExtractedDocument document = new LocalDocumentTextExtractor(registry).extract(source);

        assertEquals("Beta", document.rawText());
    }

    @Test void localExtractorRejectsUnsupportedFilesWithTheRegistryMessage() throws Exception {
        Path source = Files.write(temp.resolve("upload.exe"), new byte[] {'M', 'Z'});
        DocumentFormatRegistry registry = new DocumentFormatRegistry(List.of(fake("Alpha", ".a", "x/a", 'A')));

        var error = assertThrows(TextExtractorPort.ExtractionException.class,
                () -> new LocalDocumentTextExtractor(registry).extract(source));

        assertEquals("Formato no admitido. Formatos admitidos: Alpha.", error.getMessage());
    }

    private static DocumentFormat fake(String name, String extension, String mime, int signature) {
        TextExtractorPort extractor = source -> new ExtractedDocument(
                List.of(new PageText(1, List.of(name))), "sha");
        return new SignatureFormat(name, List.of(extension), List.of(mime), extractor, new int[] {signature});
    }
}
