package com.docanonymizer.adapter.office;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Synthetic Office documents; every name and identifier is fictitious. */
public final class OfficeFixtures {

    private OfficeFixtures() {
    }

    public static byte[] docx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Acta de prueba sintética");
            document.createParagraph().createRun().setText("Comparece Juan García Pérez con DNI 12345678Z.");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Titular");
            table.getRow(0).getCell(1).setText("María López Ruiz");
            document.write(out);
            return out.toByteArray();
        }
    }

    /** Legacy Word 97 file generated once with LibreOffice from the same synthetic text. */
    public static byte[] doc() throws IOException {
        try (InputStream in = OfficeFixtures.class.getResourceAsStream("/formats/sample.doc")) {
            return in.readAllBytes();
        }
    }

    public static byte[] zipWith(String entryName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("contenido".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
