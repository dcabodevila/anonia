package com.docanonymizer.adapter.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort.ExtractionException;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxTextExtractorTest {

    private static final String USABLE_TEXT =
            "Contrato de arrendamiento firmado por las partes en Madrid";
    private static final String OCR_TEXT = "Texto reconocido por OCR local";

    @TempDir Path dir;

    private final List<Integer> recognizedPages = new ArrayList<>();
    private final List<BufferedImage> renderedPages = new ArrayList<>();

    private final PdfBoxTextExtractor.PageOcr recordingOcr = (pageNumber, page) -> {
        recognizedPages.add(pageNumber);
        renderedPages.add(page);
        return OCR_TEXT + " " + pageNumber;
    };

    @Test
    void recognizesScannedPageWithoutTextFromTheCompleteRenderedPage() throws Exception {
        Path pdf = pdf(Page.image(PDRectangle.A4));

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(1), recognizedPages);
        assertEquals(1, document.pages().size());
        assertEquals(1, document.pages().get(0).number());
        assertEquals(OCR_TEXT + " 1", text(document.pages().get(0)));
        BufferedImage rendered = renderedPages.get(0);
        assertTrue(rendered.getWidth() > PDRectangle.A4.getWidth(), "page rendered above 72 dpi");
        assertEquals(
                PDRectangle.A4.getHeight() / PDRectangle.A4.getWidth(),
                (double) rendered.getHeight() / rendered.getWidth(),
                0.01,
                "the whole page is rendered, not an image fragment");
    }

    @Test
    void dominantImageUsesOnlyOcrTextEvenWhenThePageHasSomeText() throws Exception {
        Path pdf = pdf(Page.image(PDRectangle.A4).withText(USABLE_TEXT));

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(1), recognizedPages);
        String text = text(document.pages().get(0));
        assertEquals(OCR_TEXT + " 1", text);
        assertFalse(text.contains("arrendamiento"), "extraction and OCR are never concatenated");
    }

    @Test
    void recognizesImagesNestedInsideFormXObjects() throws Exception {
        Path pdf = pdf(Page.image(PDRectangle.A4).insideForm());

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(1), recognizedPages);
        assertEquals(OCR_TEXT + " 1", text(document.pages().get(0)));
    }

    @Test
    void smallImageWithUsableTextKeepsExactExtractionWithoutOcr() throws Exception {
        Path pdf = pdf(Page.image(new PDRectangle(40, 700, 80, 80)).withText(USABLE_TEXT));

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(), recognizedPages);
        assertTrue(text(document.pages().get(0)).contains(USABLE_TEXT));
    }

    @Test
    void textOnlyPageKeepsExactExtractionWithoutOcr() throws Exception {
        Path pdf = pdf(Page.text(USABLE_TEXT));

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(), recognizedPages);
        assertTrue(text(document.pages().get(0)).contains(USABLE_TEXT));
    }

    @Test
    void smallImageWithoutUsableTextIsRecognized() throws Exception {
        Path pdf = pdf(Page.image(new PDRectangle(40, 600, 200, 120)).withText("Firma"));

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(1), recognizedPages);
        assertEquals(OCR_TEXT + " 1", text(document.pages().get(0)));
    }

    @Test
    void mixedDocumentDecidesPerPageAndKeepsPageNumbers() throws Exception {
        Path pdf = pdf(Page.text(USABLE_TEXT), Page.image(PDRectangle.A4), Page.text(USABLE_TEXT));

        ExtractedDocument document = extractor(recordingOcr).extract(pdf);

        assertEquals(List.of(2), recognizedPages);
        assertEquals(List.of(1, 2, 3), document.pages().stream().map(PageText::number).toList());
        assertTrue(text(document.pages().get(0)).contains(USABLE_TEXT));
        assertEquals(OCR_TEXT + " 2", text(document.pages().get(1)));
        assertTrue(text(document.pages().get(2)).contains(USABLE_TEXT));
    }

    @Test
    void blankOcrResultBlocksExtractionNamingThePage() throws Exception {
        Path pdf = pdf(Page.text(USABLE_TEXT), Page.image(PDRectangle.A4));

        ExtractionException error = assertThrows(
                ExtractionException.class,
                () -> extractor((pageNumber, page) -> " \r\n \t").extract(pdf));

        assertEquals(
                "La pagina 2 no produjo texto con OCR local. Revisa la nitidez del escaneo.",
                error.getMessage());
    }

    @Test
    void ocrFailureBlocksExtractionNamingThePage() throws Exception {
        Path pdf = pdf(Page.image(PDRectangle.A4));

        ExtractionException error = assertThrows(
                ExtractionException.class,
                () -> extractor((pageNumber, page) -> {
                    throw new ExtractionException(
                            "OCR local no disponible. Instala Tesseract y el modelo espanol (spa).");
                }).extract(pdf));

        assertEquals(
                "No se pudo aplicar OCR local a la pagina 1: "
                        + "OCR local no disponible. Instala Tesseract y el modelo espanol (spa).",
                error.getMessage());
    }

    @Test
    void clippedPageSizeImageIsMeasuredByItsVisibleAreaAndKeepsExactExtraction() throws Exception {
        Path file = dir.resolve("clipped.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.addRect(40, 700, 20, 20);
                content.clip();
                content.drawImage(LosslessFactory.createFromImage(document, grayImage()),
                        0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                content.restoreGraphicsState();
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 400);
                content.showText(USABLE_TEXT);
                content.endText();
            }
            document.save(file.toFile());
        }

        ExtractedDocument document = extractor(recordingOcr).extract(file);

        assertEquals(List.of(), recognizedPages);
        assertTrue(text(document.pages().get(0)).contains(USABLE_TEXT));
    }

    @Test
    void ordinaryOcrIoFailureBlocksExtractionNamingThePage() throws Exception {
        Path pdf = pdf(Page.text(USABLE_TEXT), Page.image(PDRectangle.A4));

        ExtractionException error = assertThrows(
                ExtractionException.class,
                () -> extractor((pageNumber, page) -> {
                    throw new java.io.IOException("C:\\Users\\someone\\Temp\\page.png: disk full");
                }).extract(pdf));

        assertEquals("No se pudo aplicar OCR local a la pagina 2.", error.getMessage());
    }

    private static PdfBoxTextExtractor extractor(PdfBoxTextExtractor.PageOcr ocr) {
        return new PdfBoxTextExtractor(500, 50L * 1024 * 1024, ocr);
    }

    private static String text(PageText page) {
        return String.join("\n", page.lines()).strip();
    }

    private Path pdf(Page... pages) throws Exception {
        Path file = dir.resolve("fixture-" + System.nanoTime() + ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (Page spec : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (spec.image() != null) {
                        PDImageXObject image = LosslessFactory.createFromImage(document, grayImage());
                        PDRectangle box = spec.image();
                        if (spec.nested()) {
                            PDFormXObject form = new PDFormXObject(document);
                            form.setBBox(PDRectangle.A4);
                            form.setResources(new PDResources());
                            COSName imageName = form.getResources().add(image);
                            try (OutputStream formContent = form.getContentStream().createOutputStream()) {
                                formContent.write(String.format(java.util.Locale.ROOT,
                                        "q %.2f 0 0 %.2f %.2f %.2f cm /%s Do Q",
                                        box.getWidth(), box.getHeight(), box.getLowerLeftX(),
                                        box.getLowerLeftY(), imageName.getName())
                                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            }
                            content.drawForm(form);
                        } else {
                            content.drawImage(
                                    image, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
                        }
                    }
                    if (spec.text() != null) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                        content.newLineAtOffset(50, 400);
                        content.showText(spec.text());
                        content.endText();
                    }
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static BufferedImage grayImage() {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 40; y++) {
                image.getRaster().setSample(x, y, 0, (x + y) % 2 == 0 ? 40 : 220);
            }
        }
        return image;
    }

    private record Page(PDRectangle image, String text, boolean nested) {
        static Page image(PDRectangle area) {
            return new Page(area, null, false);
        }

        static Page text(String text) {
            return new Page(null, text, false);
        }

        Page withText(String value) {
            return new Page(image, value, nested);
        }

        Page insideForm() {
            return new Page(image, text, true);
        }
    }
}
