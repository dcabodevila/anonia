package com.docanonymizer.adapter.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.ExtractedDocument;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LocalDocumentTextExtractorTest {
    private static final String COMMAND_PROPERTY = "doc.anonymizer.tesseract.command";
    private static final String USABLE_TEXT =
            "Contrato de arrendamiento firmado por las partes en Madrid";

    @TempDir Path dir;
    private String previousCommand;

    @BeforeEach
    void rememberCommand() {
        previousCommand = System.getProperty(COMMAND_PROPERTY);
    }

    @AfterEach
    void restoreCommand() {
        if (previousCommand == null) {
            System.clearProperty(COMMAND_PROPERTY);
        } else {
            System.setProperty(COMMAND_PROPERTY, previousCommand);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void scannedPdfPageIsRecognizedWithLocalTesseractAndTemporaryImageIsRemoved() throws Exception {
        Path received = dir.resolve("received.png");
        Path receivedPath = dir.resolve("received-path.txt");
        System.setProperty(COMMAND_PROPERTY, fakeTesseract(received, receivedPath));
        Path pdf = pdf(true, null);

        ExtractedDocument document = new LocalDocumentTextExtractor().extract(pdf);

        assertEquals(1, document.pages().size());
        assertEquals(1, document.pages().get(0).number());
        assertEquals("texto ocr de pagina", String.join("\n", document.pages().get(0).lines()).strip());
        byte[] image = Files.readAllBytes(received);
        assertTrue(image.length > 8 && (image[0] & 0xff) == 0x89 && image[1] == 'P',
                "the rendered page is handed to Tesseract as PNG");
        Path temporary = Path.of(Files.readString(receivedPath, StandardCharsets.UTF_8).strip());
        assertFalse(Files.exists(temporary), "the rendered page image is deleted after OCR");
    }

    @Test
    void textPdfDoesNotNeedTesseract() throws Exception {
        System.setProperty(COMMAND_PROPERTY, dir.resolve("missing-tesseract.exe").toString());
        Path pdf = pdf(false, USABLE_TEXT);

        ExtractedDocument document = new LocalDocumentTextExtractor().extract(pdf);

        assertTrue(String.join("\n", document.pages().get(0).lines()).contains(USABLE_TEXT));
    }

    private static String fakeTesseract(Path received, Path receivedPath) throws Exception {
        Path script = received.resolveSibling("fake-tesseract.cmd");
        Files.writeString(script, String.join("\r\n",
                "@echo off",
                "copy /y \"%~1\" \"" + received + "\" >nul",
                "echo %~1> \"" + receivedPath + "\"",
                "echo texto ocr de pagina",
                ""));
        return script.toString();
    }

    private Path pdf(boolean scanned, String text) throws Exception {
        Path file = dir.resolve("document.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                if (scanned) {
                    BufferedImage scan = new BufferedImage(40, 56, BufferedImage.TYPE_BYTE_GRAY);
                    content.drawImage(LosslessFactory.createFromImage(document, scan),
                            0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
                if (text != null) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    content.newLineAtOffset(50, 400);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }
}
