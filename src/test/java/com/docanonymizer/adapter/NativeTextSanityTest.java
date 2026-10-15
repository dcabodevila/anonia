package com.docanonymizer.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.service.DocumentNotProcessableException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sanity guard protects text inferred from rendering (PDF text layer, OCR). Formats that
 * carry their text natively must not be rejected for being short or mostly numeric.
 */
class NativeTextSanityTest {

    @TempDir Path temp;

    @Test void shortPlainTextIsAnalysed() throws Exception {
        Path source = Files.writeString(temp.resolve("nota.txt"), "Informe sintético. DNI 12345678Z.");

        assertFindsDni(source);
    }

    @Test void shortMarkdownIsAnalysed() throws Exception {
        Path source = Files.writeString(temp.resolve("nota.md"), "# Nota\n\nDNI 12345678Z");

        assertFindsDni(source);
    }

    @Test void numericXlsxIsAnalysed() throws Exception {
        Path source = Files.write(temp.resolve("datos.xlsx"), numericWorkbook(new XSSFWorkbook()));

        assertFindsDni(source);
    }

    @Test void numericXlsIsAnalysed() throws Exception {
        Path source = Files.write(temp.resolve("datos.xls"), numericWorkbook(new HSSFWorkbook()));

        assertFindsDni(source);
    }

    @Test void nativeDocumentWithoutTextIsRejectedWithAFormatNeutralMessage() throws Exception {
        Path source = Files.writeString(temp.resolve("vacio.txt"), " \n\t\n");

        var error = assertThrows(DocumentNotProcessableException.class,
                () -> PipelineFactory.standard().analyze(source));

        assertEquals("El documento no contiene texto.", error.getMessage());
    }

    @Test void shortPdfIsStillRejectedByTheSanityGuard() throws Exception {
        Path pdf = temp.resolve("corto.pdf");
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("DNI 12345678Z");
                stream.endText();
            }
            document.save(pdf.toFile());
        }

        var error = assertThrows(DocumentNotProcessableException.class,
                () -> PipelineFactory.standard().analyze(pdf));

        assertTrue(error.getMessage().startsWith(
                "Extraccion no fiable: el documento no tiene capa de texto utilizable"), error.getMessage());
    }

    private static void assertFindsDni(Path source) throws Exception {
        var candidates = PipelineFactory.standard().analyze(source).candidates();

        assertTrue(candidates.stream().anyMatch(d -> d.type() == DetectionType.DNI
                && d.value().equals("12345678Z")), () -> "Candidates: " + candidates);
    }

    private static byte[] numericWorkbook(Workbook workbook) throws Exception {
        try (workbook) {
            Sheet sheet = workbook.createSheet("Datos");
            for (int i = 0; i < 10; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("12345678Z");
                row.createCell(1).setCellValue(1500 + i);
                row.createCell(2).setCellValue(20240101 + i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
