package com.docanonymizer.adapter;

import static com.docanonymizer.domain.model.DetectionType.PERSON;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A name already found in one row is not chased across the cells or rows of a later one. */
class BoundaryPropagationTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"cells", "rows", "spaced"})
    void propagatedNamesStayInsideOneCellAndOneRow(String mode) throws Exception {
        Path workbook = dir.resolve("people.xlsx");
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(workbook)) {
            var sheet = book.createSheet("Personas");
            sheet.createRow(0).createCell(0).setCellValue("Juan García Pérez");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(mode.equals("spaced") ? "J U A N   G A R C Í A " : "Juan García");
            (mode.equals("rows") ? sheet.createRow(2).createCell(0) : row.createCell(1))
                    .setCellValue(mode.equals("spaced") ? " P É R E Z" : "Pérez");
            book.write(out);
        }

        for (var detection : PipelineFactory.standard().analyze(workbook).candidates()) {
            if (detection.type() == PERSON) {
                assertFalse(detection.value().contains("\t") || detection.value().contains("\n\n"),
                        detection::value);
            }
        }
    }
}
