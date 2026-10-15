package com.docanonymizer.adapter.pdf;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
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

    /** Recognizes the text of one rendered PDF page with local OCR. */
    @FunctionalInterface
    public interface PageOcr {
        String recognize(int pageNumber, BufferedImage page) throws IOException;
    }

    private static final int MIN_PAGE_TEXT_CHARS = 40;
    private static final double DOMINANT_IMAGE_COVERAGE = 0.5;
    private static final float OCR_DPI = 300;

    private final int maxPages;
    private final long maxBytes;
    private final PageOcr pageOcr;

    public PdfBoxTextExtractor() {
        this(500, 50L * 1024 * 1024);
    }

    public PdfBoxTextExtractor(int maxPages, long maxBytes) {
        this(maxPages, maxBytes, null);
    }

    public PdfBoxTextExtractor(int maxPages, long maxBytes, PageOcr pageOcr) {
        this.maxPages = maxPages;
        this.maxBytes = maxBytes;
        this.pageOcr = pageOcr;
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
        PDFRenderer renderer = new PDFRenderer(document);
        for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String text = stripper.getText(document);
            if (pageOcr != null && needsOcr(document.getPage(pageNumber - 1), text)) {
                text = recognize(renderer, pageNumber);
            }
            // El tabulador solo es frontera de celda en Word/Excel nativos; un PDF no tiene
            // celdas, asi que un codigo de glifo que su ToUnicode mapea a U+0009 (o un tabulador
            // del OCR de la pagina) se trata como el espacio que es.
            text = text.replace('\t', ' ');
            pages.add(new PageText(pageNumber, Arrays.asList(text.split("\\R", -1))));
        }
        return pages;
    }

    /**
     * A page goes through OCR when it draws images and either its text layer is not usable
     * or its images cover most of the page, as a scan does. Small logos and stamps keep the
     * exact text layer, so text PDFs never depend on Tesseract.
     */
    private static boolean needsOcr(PDPage page, String text) throws IOException {
        double coverage = ImageCoverage.of(page);
        if (coverage == 0) {
            return false;
        }
        return meaningfulChars(text) < MIN_PAGE_TEXT_CHARS || coverage >= DOMINANT_IMAGE_COVERAGE;
    }

    /** Renders the complete page and replaces its text: extraction and OCR are never mixed. */
    private String recognize(PDFRenderer renderer, int pageNumber) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, OCR_DPI, ImageType.GRAY);
        String recognized;
        try {
            recognized = pageOcr.recognize(pageNumber, image);
        } catch (ExtractionException e) {
            throw new ExtractionException(
                    "No se pudo aplicar OCR local a la pagina " + pageNumber + ": " + e.getMessage(), e);
        } catch (IOException e) {
            // The cause may name temporary paths, so only the page reaches the message.
            throw new ExtractionException("No se pudo aplicar OCR local a la pagina " + pageNumber + ".", e);
        }
        if (recognized == null || recognized.isBlank()) {
            throw new ExtractionException("La pagina " + pageNumber
                    + " no produjo texto con OCR local. Revisa la nitidez del escaneo.");
        }
        return recognized;
    }

    private static int meaningfulChars(String text) {
        return (int) text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
    }

    /** Fraction of the page area painted by images, including images nested in forms. */
    private static final class ImageCoverage extends PDFGraphicsStreamEngine {
        private final Area painted = new Area();
        private final GeneralPath path = new GeneralPath();
        private int pendingClipRule = -1;

        private ImageCoverage(PDPage page) {
            super(page);
        }

        static double of(PDPage page) throws IOException {
            ImageCoverage coverage = new ImageCoverage(page);
            coverage.processPage(page);
            PDRectangle box = page.getCropBox();
            Rectangle2D pageArea = new Rectangle2D.Float(
                    box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
            Area visible = new Area(pageArea);
            visible.intersect(coverage.painted);
            return area(visible) / (pageArea.getWidth() * pageArea.getHeight());
        }

        private static double area(Area region) {
            double total = 0;
            double[] point = new double[6];
            double startX = 0;
            double startY = 0;
            double lastX = 0;
            double lastY = 0;
            for (PathIterator it = region.getPathIterator(null); !it.isDone(); it.next()) {
                switch (it.currentSegment(point)) {
                    case PathIterator.SEG_MOVETO -> {
                        startX = lastX = point[0];
                        startY = lastY = point[1];
                    }
                    case PathIterator.SEG_LINETO -> {
                        total += lastX * point[1] - point[0] * lastY;
                        lastX = point[0];
                        lastY = point[1];
                    }
                    case PathIterator.SEG_CLOSE -> {
                        total += lastX * startY - startX * lastY;
                        lastX = startX;
                        lastY = startY;
                    }
                    default -> {
                        // Unions of transformed rectangles only contain straight segments.
                    }
                }
            }
            return Math.abs(total) / 2;
        }

        @Override
        public void drawImage(PDImage image) {
            AffineTransform transform =
                    getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
            Area visible = new Area(transform.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)));
            visible.intersect(getGraphicsState().getCurrentClippingPath());
            painted.add(visible);
        }

        // Path operators are tracked only so clipping paths limit the measured image area.

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            path.moveTo(p0.getX(), p0.getY());
            path.lineTo(p1.getX(), p1.getY());
            path.lineTo(p2.getX(), p2.getY());
            path.lineTo(p3.getX(), p3.getY());
            path.closePath();
        }

        @Override
        public void clip(int windingRule) {
            pendingClipRule = windingRule;
        }

        @Override
        public void moveTo(float x, float y) {
            path.moveTo(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            path.lineTo(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            Point2D current = path.getCurrentPoint();
            return current == null ? new Point2D.Float() : current;
        }

        @Override
        public void closePath() {
            path.closePath();
        }

        @Override
        public void endPath() {
            consumePath();
        }

        @Override
        public void strokePath() {
            consumePath();
        }

        @Override
        public void fillPath(int windingRule) {
            consumePath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            consumePath();
        }

        @Override
        public void shadingFill(COSName shadingName) {}

        /** A pending W/W* clip takes effect when the path is painted or ended, as in PDF. */
        private void consumePath() {
            if (pendingClipRule != -1) {
                path.setWindingRule(pendingClipRule);
                getGraphicsState().intersectClippingPath(path);
                pendingClipRule = -1;
            }
            path.reset();
        }
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
