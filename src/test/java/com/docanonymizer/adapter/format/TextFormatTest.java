package com.docanonymizer.adapter.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextFormatTest {

    private static final byte[] TEXT = "Juan Pérez, DNI 12345678Z".getBytes(StandardCharsets.UTF_8);

    private final DocumentFormatRegistry registry = DocumentFormatRegistry.standard();

    @Test void markdownAndPlainTextAreRecognisedByTheirDeclaredExtension() throws Exception {
        assertEquals("Markdown", registry.require(DocumentProbe.of(TEXT, "notas.md")).displayName());
        assertEquals("Markdown", registry.require(DocumentProbe.of(TEXT, "NOTAS.Markdown")).displayName());
        assertEquals("TXT", registry.require(DocumentProbe.of(TEXT, "acta.txt")).displayName());
    }

    @Test void textWithoutADeclaredExtensionIsNotRecognised() throws Exception {
        assertTrue(registry.resolve(DocumentProbe.of(TEXT, null)).isEmpty());
        assertTrue(registry.resolve(DocumentProbe.of(TEXT, "acta.exe")).isEmpty());
    }

    @Test void binaryContentNamedAsTextIsNotRecognised() throws Exception {
        byte[] binary = {'M', 'Z', 0, 0, 3, 0, 0, 0};

        assertTrue(registry.resolve(DocumentProbe.of(binary, "acta.txt")).isEmpty());
    }

    @Test void utf16TextWithByteOrderMarkIsStillText() throws Exception {
        byte[] utf16 = "\uFEFFJuan".getBytes(StandardCharsets.UTF_16LE);

        assertEquals("TXT", registry.require(DocumentProbe.of(utf16, "acta.txt")).displayName());
    }

    @Test void realSignatureWinsOverTextExtension() throws Exception {
        byte[] pdf = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);

        assertEquals("PDF", registry.require(DocumentProbe.of(pdf, "acta.txt")).displayName());
    }
}
