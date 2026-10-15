package com.docanonymizer.adapter.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.ExtractedDocument;
import java.io.OutputStream;
import java.nio.file.Path;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A PDF text layer can map a glyph to U+0009 via a custom ToUnicode CMap. That tab is not a
 * table or spreadsheet cell boundary, so it must become a plain space, not a structural cue.
 */
class PdfBoxTabExtractionTest {

    @TempDir Path temp;

    @Test
    void extractedTabsBecomePlainSpaces() throws Exception {
        Path pdf = temp.resolve("tab.pdf");
        writeTabPdf(pdf, "D. \tJuan. Documento de prueba con texto suficiente para analizar sin errores.");

        ExtractedDocument document = new PdfBoxTextExtractor().extract(pdf);

        String text = document.pages().get(0).lines().get(0);
        assertFalse(text.contains("\t"), text);
        assertTrue(text.contains("D.  Juan."), text);
    }

    @Test
    void cueTabFromThePdfTextLayerIsStillDetectedAsAPerson() throws Exception {
        Path pdf = temp.resolve("tab-cue.pdf");
        writeTabPdf(pdf, "D. \tJuan. Documento de prueba con texto suficiente para analizar sin errores.");

        var candidates = PipelineFactory.standard().analyze(pdf).candidates();

        assertTrue(candidates.stream()
                .anyMatch(d -> d.type() == DetectionType.PERSON && d.value().equals("Juan")),
                () -> "Candidates: " + candidates);
    }

    /** Builds a PDF whose single tab glyph maps through its font's ToUnicode CMap to U+0009. */
    private static void writeTabPdf(Path pdf, String textWithTab) throws Exception {
        int tabAt = textWithTab.indexOf('\t');
        String before = textWithTab.substring(0, tabAt);
        String after = textWithTab.substring(tabAt + 1);

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDStream cmapStream = new PDStream(document);
            try (OutputStream out = cmapStream.createOutputStream()) {
                out.write(("/CIDInit /ProcSet findresource begin\n"
                        + "12 dict begin\n"
                        + "begincmap\n"
                        + "1 begincodespacerange\n"
                        + "<00> <ff>\n"
                        + "endcodespacerange\n"
                        + "1 beginbfchar\n"
                        + "<01> <0009>\n"
                        + "endbfchar\n"
                        + "endcmap\n"
                        + "end\n"
                        + "end\n").getBytes("ISO-8859-1"));
            }
            font.getCOSObject().setItem(COSName.getPDFName("ToUnicode"), cmapStream.getCOSObject());

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(before);
                stream.appendRawCommands("<01> Tj\n");
                stream.showText(after);
                stream.endText();
            }
            document.save(pdf.toFile());
        }
    }
}
