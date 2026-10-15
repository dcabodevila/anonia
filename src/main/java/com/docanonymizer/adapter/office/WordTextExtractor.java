package com.docanonymizer.adapter.office;

import com.docanonymizer.adapter.format.SourceFiles;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * Extracts Word text (.docx and legacy .doc) as a single page: Word has no stable page
 * boundaries without a layout engine. Table cells are separated by tabs.
 */
public final class WordTextExtractor implements TextExtractorPort {

    private final long maxBytes;

    public WordTextExtractor() {
        this(SourceFiles.DEFAULT_MAX_BYTES);
    }

    public WordTextExtractor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        byte[] content = SourceFiles.readBounded(source, maxBytes);
        String text;
        try {
            text = FileMagic.valueOf(content) == FileMagic.OLE2 ? legacyText(content) : ooxmlText(content);
        } catch (IOException | RuntimeException e) {
            // POI messages may describe the file structure; never forward them to the user.
            throw new ExtractionException("No se pudo leer el documento de Word.");
        }
        return ExtractedDocument.nativeText(List.of(new PageText(1, SourceFiles.lines(text))),
                SourceFiles.sha256(content));
    }

    private static String legacyText(byte[] content) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
                WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String ooxmlText(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
                XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }
}
