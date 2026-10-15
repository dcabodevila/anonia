package com.docanonymizer.adapter.office;

import com.docanonymizer.adapter.format.SourceFiles;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Extracts Excel workbook text (.xlsx and legacy .xls) as a single page.
 *
 * <p>Each sheet starts with a {@code ## <sheet name>} line followed by one line per non-empty
 * row, cells separated by tabs as Excel displays them. Formulas contribute their cached result,
 * never their expression, and are not re-evaluated. A single page keeps column headers that
 * repeat across sheets from being removed as page headers.
 */
public final class ExcelTextExtractor implements TextExtractorPort {

    private final long maxBytes;

    public ExcelTextExtractor() {
        this(SourceFiles.DEFAULT_MAX_BYTES);
    }

    public ExcelTextExtractor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        byte[] content = SourceFiles.readBounded(source, maxBytes);
        List<String> lines;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            lines = lines(workbook);
        } catch (IOException | RuntimeException e) {
            // POI messages may describe the file structure; never forward them to the user.
            throw new ExtractionException("No se pudo leer el libro de Excel.");
        }
        return new ExtractedDocument(List.of(new PageText(1, lines)), SourceFiles.sha256(content));
    }

    private static List<String> lines(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        formatter.setUseCachedValuesForFormulaCells(true);
        List<String> lines = new ArrayList<>();
        for (Sheet sheet : workbook) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("## " + sheet.getSheetName());
            for (Row row : sheet) {
                String line = rowText(row, formatter);
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static String rowText(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        for (int column = 0; column < Math.max(row.getLastCellNum(), 0); column++) {
            Cell cell = row.getCell(column);
            cells.add(cell == null ? "" : formatter.formatCellValue(cell).replaceAll("\\R", " "));
        }
        int end = cells.size();
        while (end > 0 && cells.get(end - 1).isBlank()) {
            end--;
        }
        return String.join("\t", cells.subList(0, end));
    }
}
