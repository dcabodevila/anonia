package com.docanonymizer.adapter.pdf;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extractor de texto sobre Apache PDFBox 3.
 *
 * <p>Extrae pagina a pagina en lugar de todo de golpe porque el limpiador de cabeceras y
 * pies necesita saber que linea pertenece a que pagina; una vez concatenado, esa
 * informacion se pierde.
 *
 * <p>{@code setSortByPosition(true)} ordena por posicion geometrica en vez de por orden
 * de aparicion en el content stream. Sin esto, un PDF puede devolver el texto en el orden
 * en que se dibujo, que no tiene por que ser el orden de lectura.
 */
public final class PdfBoxTextExtractor implements TextExtractorPort {

    private final int maxPages;
    private final long maxBytes;

    public PdfBoxTextExtractor() {
        this(500, 50L * 1024 * 1024);
    }

    public PdfBoxTextExtractor(int maxPages, long maxBytes) {
        this.maxPages = maxPages;
        this.maxBytes = maxBytes;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new ExtractionException("La ruta no es un fichero legible.");
        }
        long size = Files.size(source);
        if (size > maxBytes) {
            throw new ExtractionException(
                    "El fichero supera el limite configurado (" + size + " > " + maxBytes
                            + " bytes).");
        }
        if (!hasPdfSignature(source)) {
            throw new ExtractionException(
                    "El fichero no empieza por la firma %PDF-. No se confia en la extension.");
        }

        String sha256 = sha256(source);

        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (document.isEncrypted()) {
                throw new ExtractionException(
                        "El PDF esta cifrado. Fuera del alcance de este prototipo.");
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new ExtractionException("El PDF no tiene paginas.");
            }
            if (pageCount > maxPages) {
                throw new ExtractionException(
                        "El PDF tiene " + pageCount + " paginas y el limite es " + maxPages + ".");
            }
            return new ExtractedDocument(readPages(document, pageCount), sha256);
        } catch (InvalidPasswordException e) {
            throw new ExtractionException(
                    "El PDF requiere contrasena. Fuera del alcance de este prototipo.", e);
        }
    }

    private List<PageText> readPages(PDDocument document, int pageCount) throws IOException {
        List<PageText> pages = new ArrayList<>(pageCount);
        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String text = stripper.getText(document);
            pages.add(new PageText(pageNumber, Arrays.asList(text.split("\\R", -1))));
        }
        return pages;
    }

    /** No se confia en la extension ni en el MIME declarado: se mira la cabecera real. */
    private boolean hasPdfSignature(Path source) throws IOException {
        byte[] header = new byte[5];
        try (var in = Files.newInputStream(source)) {
            int read = in.read(header);
            if (read < header.length) {
                return false;
            }
        }
        return header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                && header[3] == 'F' && header[4] == '-';
    }

    private String sha256(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(source));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
