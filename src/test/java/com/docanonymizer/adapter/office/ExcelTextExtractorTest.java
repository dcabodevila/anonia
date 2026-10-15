package com.docanonymizer.adapter.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.format.DocumentFormatRegistry;
import com.docanonymizer.adapter.format.DocumentProbe;
import com.docanonymizer.adapter.format.SourceFiles;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelTextExtractorTest {

    private static final List<String> EXPECTED_LINES = List.of(
            "## Clientes",
            "",
            "Nombre\tDNI\tImporte",
            "",
            "Juan García Pérez\t12345678Z\t1500\t3000",
            "",
            "## Proveedores",
            "",
            "Nombre\tDNI\tImporte",
            "",
            "María López Ruiz\t87654321X\t1500\t3000");

    @TempDir Path temp;

    private final DocumentFormatRegistry registry = DocumentFormatRegistry.standard();

    @Test void registryRecognisesXlsxAndXlsByContentWhateverTheDeclaredName() throws Exception {
        DocumentProbe xlsx = DocumentProbe.of(OfficeFixtures.xlsx(), "upload.bin");
        DocumentProbe xls = DocumentProbe.of(OfficeFixtures.xls(), "libro.doc");

        assertEquals("Excel", registry.require(xlsx).displayName());
        assertEquals(".xlsx", registry.require(xlsx).extensions().get(0));
        assertEquals("Excel", registry.require(xls).displayName());
        assertEquals(".xls", registry.require(xls).extensions().get(0));
    }

    @Test void extractsEverySheetAsHeadedRowsOfFormattedCellsInOnePage() throws Exception {
        byte[] content = OfficeFixtures.xlsx();
        Path source = Files.write(temp.resolve("libro.xlsx"), content);

        ExtractedDocument document = new ExcelTextExtractor().extract(source);

        assertEquals(1, document.pageCount());
        assertEquals(EXPECTED_LINES, document.pages().get(0).lines());
        assertEquals(SourceFiles.sha256(content), document.sourceSha256());
    }

    @Test void extractsLegacyXlsTheSameWay() throws Exception {
        Path source = Files.write(temp.resolve("libro.xls"), OfficeFixtures.xls());

        assertEquals(EXPECTED_LINES, new ExcelTextExtractor().extract(source).pages().get(0).lines());
    }

    @Test void corruptWorkbooksFailWithoutDocumentContent() throws Exception {
        Path source = Files.write(temp.resolve("libro.xlsx"),
                "PK\u0003\u0004Juan García corrupto".getBytes(StandardCharsets.ISO_8859_1));

        var error = assertThrows(TextExtractorPort.ExtractionException.class,
                () -> new ExcelTextExtractor().extract(source));

        assertEquals("No se pudo leer el libro de Excel.", error.getMessage());
    }

    @Test void unsupportedMessageListsEveryRegisteredFormatOnce() {
        assertTrue(registry.unsupportedMessage().equals(
                "Formato no admitido. Formatos admitidos: PDF, JPEG, PNG, Word, Excel, Markdown, TXT."),
                registry.unsupportedMessage());
    }

    @Test void sparseRowsKeepOnlyTheirNonBlankCells() throws Exception {
        byte[] content;
        try (Workbook workbook = new XSSFWorkbook()) {
            Row row = workbook.createSheet("Datos").createRow(0);
            row.createCell(0).setCellValue("Juan García Pérez");
            row.createCell(2).setCellValue("12345678Z");
            row.createCell(5).setCellValue("");
            row.createCell(16_383).setCellValue("Madrid");
            content = bytes(workbook);
        }
        Path source = Files.write(temp.resolve("disperso.xlsx"), content);

        assertEquals(List.of("## Datos", "", "Juan García Pérez\t12345678Z\tMadrid"),
                new ExcelTextExtractor().extract(source).pages().get(0).lines());
    }

    private static byte[] bytes(Workbook workbook) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    @Test void sheetsWithoutTextAreLeftOut() throws Exception {
        byte[] content;
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Vacía");
            workbook.createSheet("Datos").createRow(0).createCell(0).setCellValue("12345678Z");
            workbook.createSheet("Blancos").createRow(3).createCell(1).setCellValue("  ");
            content = bytes(workbook);
        }
        Path source = Files.write(temp.resolve("hojas.xlsx"), content);

        assertEquals(List.of("## Datos", "", "12345678Z"),
                new ExcelTextExtractor().extract(source).pages().get(0).lines());
    }

    @Test void cellsAreFormattedInSpanishWhateverTheMachineLocale() throws Exception {
        byte[] content;
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle amount = workbook.createCellStyle();
            amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            Cell cell = workbook.createSheet("Importes").createRow(0).createCell(0);
            cell.setCellValue(1234.5);
            cell.setCellStyle(amount);
            content = bytes(workbook);
        }
        Path source = Files.write(temp.resolve("importes.xlsx"), content);

        Locale original = Locale.getDefault();
        try {
            for (Locale machine : List.of(Locale.US, Locale.GERMANY, Locale.ROOT)) {
                Locale.setDefault(machine);
                assertEquals(List.of("## Importes", "", "1.234,50"),
                        new ExcelTextExtractor().extract(source).pages().get(0).lines(), machine.toString());
            }
        } finally {
            Locale.setDefault(original);
        }
    }
}
