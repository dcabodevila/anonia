package com.docanonymizer.adapter.office;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WordTextExtractorTest {

    @TempDir Path temp;

    private final DocumentFormatRegistry registry = DocumentFormatRegistry.standard();

    @Test void registryRecognisesDocxAndDocByContentWhateverTheDeclaredName() throws Exception {
        assertEquals("Word", registry.require(DocumentProbe.of(OfficeFixtures.docx(), "upload.bin")).displayName());
        assertEquals("Word", registry.require(DocumentProbe.of(OfficeFixtures.doc(), null)).displayName());
        assertEquals(".docx", registry.require(DocumentProbe.of(OfficeFixtures.docx(), null)).extensions().get(0));
        assertEquals(".doc", registry.require(DocumentProbe.of(OfficeFixtures.doc(), null)).extensions().get(0));
    }

    @Test void zipArchivesWithoutAWordBodyAreNotWord() throws Exception {
        assertTrue(registry.resolve(DocumentProbe.of(OfficeFixtures.zipWith("notas.txt"), "fake.docx")).isEmpty());
    }

    @Test void extractsDocxParagraphsAndTableCells() throws Exception {
        byte[] content = OfficeFixtures.docx();
        Path source = Files.write(temp.resolve("acta.docx"), content);

        ExtractedDocument document = new WordTextExtractor().extract(source);

        assertEquals(1, document.pageCount());
        assertTrue(document.pages().get(0).lines().contains("Comparece Juan García Pérez con DNI 12345678Z."),
                document.rawText());
        assertTrue(document.rawText().contains("María López Ruiz"), document.rawText());
        assertEquals(SourceFiles.sha256(content), document.sourceSha256());
    }

    @Test void extractsLegacyDocText() throws Exception {
        Path source = Files.write(temp.resolve("acta.doc"), OfficeFixtures.doc());

        String text = new WordTextExtractor().extract(source).rawText();

        assertTrue(text.contains("Comparece Juan García Pérez con DNI 12345678Z."), text);
        assertTrue(text.contains("María López Ruiz"), text);
    }

    @Test void corruptWordFilesFailWithoutDocumentContent() throws Exception {
        byte[] corrupt = "PK\u0003\u0004Juan García corrupto".getBytes(StandardCharsets.ISO_8859_1);
        Path source = Files.write(temp.resolve("acta.docx"), corrupt);

        var error = assertThrows(TextExtractorPort.ExtractionException.class,
                () -> new WordTextExtractor().extract(source));

        assertEquals("No se pudo leer el documento de Word.", error.getMessage());
        assertFalse(error.getMessage().contains("Juan"));
    }
}
