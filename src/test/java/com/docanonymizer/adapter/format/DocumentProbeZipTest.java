package com.docanonymizer.adapter.format;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recognising a container must not inflate its entries: a small ZIP can expand to gigabytes
 * before the entry the format looks for. The probe reads the central directory only.
 */
class DocumentProbeZipTest {

    @TempDir Path temp;

    @Test void findsAnEntryWithoutInflatingThePrecedingOnes() throws Exception {
        byte[] zip = corruptFirstEntry(zip("padding.bin", "word/document.xml"));

        assertTrue(DocumentProbe.of(Files.write(temp.resolve("a.docx"), zip)).containsZipEntry("word/document.xml"));
        assertTrue(DocumentProbe.of(zip, "a.docx").containsZipEntry("word/document.xml"));
    }

    @Test void missingEntriesAndNonZipContentAreNotFound() throws Exception {
        byte[] zip = zip("padding.bin", "xl/workbook.xml");
        byte[] text = "no es un zip".getBytes(StandardCharsets.UTF_8);

        assertFalse(DocumentProbe.of(Files.write(temp.resolve("b.docx"), zip)).containsZipEntry("word/document.xml"));
        assertFalse(DocumentProbe.of(zip, "b.docx").containsZipEntry("word/document.xml"));
        assertFalse(DocumentProbe.of(Files.write(temp.resolve("c.docx"), text)).containsZipEntry("word/document.xml"));
        assertFalse(DocumentProbe.of(text, "c.docx").containsZipEntry("word/document.xml"));
    }

    private static byte[] zip(String... names) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(("contenido sintetico de " + name).repeat(50).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Overwrites the first entry's deflate stream with an invalid block type. */
    private static byte[] corruptFirstEntry(byte[] zip) {
        int nameLength = (zip[26] & 0xff) | (zip[27] & 0xff) << 8;
        int extraLength = (zip[28] & 0xff) | (zip[29] & 0xff) << 8;
        int data = 30 + nameLength + extraLength;
        byte[] corrupt = zip.clone();
        corrupt[data] = (byte) 0xff;
        corrupt[data + 1] = (byte) 0xff;
        return corrupt;
    }
}
