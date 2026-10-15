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
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Extracts Excel workbook text (.xlsx and legacy .xls) as a single page.
 *
 * <p>Each sheet with text starts with a {@code ## <sheet name>} line followed by one line per
 * non-empty row: its non-blank cells, formatted as Excel displays them and separated by tabs.
 * Blank cells are skipped rather than padded, so a far sparse cell cannot inflate the output.
 * Rows are separated by blank lines, so no name spans two rows. Sheets without text are left
 * out. Formulas contribute their cached result, never their expression, and are not
 * re-evaluated. A single page keeps column headers that repeat across sheets from being
 * removed as page headers.
 */
public final class ExcelTextExtractor implements TextExtractorPort {

    /**
     * Cells are rendered as a Spanish Excel would show them, not in the JVM default locale, so
     * the same workbook yields the same text on every machine.
     */
    private static final Locale CELL_LOCALE = Locale.forLanguageTag("es-ES");

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
        return ExtractedDocument.nativeText(List.of(new PageText(1, lines)), SourceFiles.sha256(content));
    }

    private static List<String> lines(Workbook workbook) {
        DataFormatter formatter = new DataFormatter(CELL_LOCALE);
        formatter.setUseCachedValuesForFormulaCells(true);
        List<String> lines = new ArrayList<>();
        for (Sheet sheet : workbook) {
            List<String> rows = new ArrayList<>();
            for (Row row : sheet) {
                String line = rowText(row, formatter);
                if (!line.isBlank()) {
                    rows.add(line);
                }
            }
            if (rows.isEmpty()) {
                // A heading alone would make a workbook without text look non-empty.
                continue;
            }
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("## " + sheet.getSheetName());
            // A blank line makes every row its own paragraph, which names never cross.
            for (String row : rows) {
                lines.add("");
                lines.add(row);
            }
        }
        return lines;
    }

    private static String rowText(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        for (Cell cell : row) {
            String value = formatter.formatCellValue(cell).replaceAll("\\R", " ");
            if (!value.isBlank()) {
                cells.add(value);
            }
        }
        return String.join("\t", cells);
    }
}
