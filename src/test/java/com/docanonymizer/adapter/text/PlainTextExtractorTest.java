package com.docanonymizer.adapter.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.docanonymizer.adapter.format.SourceFiles;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlainTextExtractorTest {

    @TempDir Path temp;

    @Test void readsUtf8AsOnePageKeepingLinesAndDroppingTheByteOrderMark() throws Exception {
        byte[] content = "\uFEFF# Acta\r\nJosé Pérez\n\nDNI 12345678Z".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(temp.resolve("acta.md"), content);

        ExtractedDocument document = new PlainTextExtractor().extract(source);

        assertEquals(1, document.pageCount());
        assertEquals(List.of("# Acta", "José Pérez", "", "DNI 12345678Z"), document.pages().get(0).lines());
        assertEquals(SourceFiles.sha256(content), document.sourceSha256());
    }

    @Test void fallsBackToWindows1252WhenTheContentIsNotValidUtf8() throws Exception {
        Path source = Files.write(temp.resolve("acta.txt"), "Peña Muñoz".getBytes(Charset.forName("windows-1252")));

        assertEquals("Peña Muñoz", new PlainTextExtractor().extract(source).rawText());
    }

    @Test void decodesUtf16WithByteOrderMark() throws Exception {
        Path source = Files.write(temp.resolve("acta.txt"), "\uFEFFJosé".getBytes(StandardCharsets.UTF_16BE));

        assertEquals("José", new PlainTextExtractor().extract(source).rawText());
    }

    @Test void rejectsFilesOverTheLimitWithoutDocumentContent() throws Exception {
        Path source = Files.writeString(temp.resolve("acta.txt"), "Juan Secreto");

        var error = assertThrows(TextExtractorPort.ExtractionException.class,
                () -> new PlainTextExtractor(4).extract(source));

        assertFalse(error.getMessage().contains("Juan"));
    }
}
