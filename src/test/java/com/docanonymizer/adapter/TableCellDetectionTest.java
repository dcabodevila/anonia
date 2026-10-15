package com.docanonymizer.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.DetectionType;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A person name never spans two table cells nor two spreadsheet rows. */
class TableCellDetectionTest {

    @TempDir Path temp;

    @Test void wordTableLabelsStayOutOfTheNameInTheNextCell() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Acta de prueba sintética");
            XWPFTable table = document.createTable(1, 2);
            // Trailing space: the cell boundary must survive whitespace normalisation.
            table.getRow(0).getCell(0).setText("Titular ");
            table.getRow(0).getCell(1).setText("María López Ruiz");
            document.write(out);
            content = out.toByteArray();
        }

        List<String> persons = persons(Files.write(temp.resolve("tabla.docx"), content));

        assertTrue(persons.contains("María López Ruiz"), persons::toString);
        assertTrue(persons.stream().noneMatch(p -> p.contains("Titular")), persons::toString);
    }

    @Test void spreadsheetHeadersAndSheetNamesStayOutOfNames() throws Exception {
        for (Workbook workbook : List.of(new XSSFWorkbook(), new HSSFWorkbook())) {
            String name = workbook instanceof XSSFWorkbook ? "libro.xlsx" : "libro.xls";
            List<String> persons = persons(Files.write(temp.resolve(name), workbook(workbook)));

            assertTrue(persons.stream().anyMatch(p -> p.contains("Juan García Pérez")), persons::toString);
            for (String label : List.of("Comparecientes", "Descripción", "Importe", "Titular")) {
                assertTrue(persons.stream().noneMatch(p -> p.contains(label)), () -> name + " " + persons);
            }
        }
    }

    private static byte[] workbook(Workbook workbook) throws Exception {
        try (workbook; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Comparecientes");
            String[][] rows = {{"Descripción", "Importe"},
                    {"Comparece Juan García Pérez con DNI 12345678Z.", "1500"},
                    {"Titular", "María López Ruiz"}};
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(rows[i][0]);
                row.createCell(1).setCellValue(rows[i][1]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static List<String> persons(Path source) throws Exception {
        return PipelineFactory.standard().analyze(source).candidates().stream()
                .filter(d -> d.type() == DetectionType.PERSON)
                .map(d -> d.value())
                .toList();
    }
}
