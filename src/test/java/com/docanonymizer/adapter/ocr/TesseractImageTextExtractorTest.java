package com.docanonymizer.adapter.ocr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.port.TextExtractorPort.ExtractionException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TesseractImageTextExtractorTest {
    private static final String COMMAND_PROPERTY = "doc.anonymizer.tesseract.command";
    private Path createdBundle;
    private String previousCommand;

    @AfterEach
    void restorePropertyAndFixture() throws Exception {
        if (previousCommand == null) {
            System.clearProperty(COMMAND_PROPERTY);
        } else {
            System.setProperty(COMMAND_PROPERTY, previousCommand);
        }
        if (createdBundle != null) {
            try (var paths = Files.walk(createdBundle)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        }
    }

    @Test
    void usesCompletePrivateBundleBesideCodeSourceBeforeAmbientCommand() throws Exception {
        Path bundle = createCompleteBundle();
        clearDeveloperOverride();

        assertEquals(bundle.resolve("tesseract.exe").toString(), configuredCommand());
        assertEquals(
                java.util.List.of("--tessdata-dir", bundle.resolve("tessdata").toString()),
                commandLine(new TesseractImageTextExtractor()).subList(5, 7));
    }

    @Test
    void keepsExplicitDeveloperOverrideAheadOfPrivateBundle() throws Exception {
        createCompleteBundle();
        String override = "C:\\developer\\tesseract.exe";
        setDeveloperOverride(override);

        assertEquals(override, configuredCommand());
    }

    @Test
    void defersIncompletePrivateBundleRejectionUntilImageExtractionWithoutExternalFallback() throws Exception {
        Path bundle = createBundleDirectory();
        Files.writeString(bundle.resolve("tesseract.exe"), "fixture");
        clearDeveloperOverride();
        Path image = Files.createTempFile("incomplete-private-ocr-", ".png");
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        TesseractImageTextExtractor extractor = assertDoesNotThrow(
                (org.junit.jupiter.api.function.ThrowingSupplier<TesseractImageTextExtractor>)
                        TesseractImageTextExtractor::new);

        ExtractionException failure = assertThrows(ExtractionException.class, () -> extractor.extract(image));
        assertEquals(
                "El OCR incluido esta incompleto. Reinstala la aplicacion; no se usara OCR externo.",
                failure.getMessage());
        Files.deleteIfExists(image);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rotatesExifOrientedPhotoBeforeOcrAndRemovesTemporaryImage(@TempDir Path dir) throws Exception {
        Path photo = dir.resolve("photo.jpg");
        Files.write(photo, jpegWithOrientation(40, 20, 6));
        Path received = dir.resolve("received.png");
        Path receivedPath = dir.resolve("received-path.txt");

        ExtractedDocument document = new TesseractImageTextExtractor(
                        fakeTesseract(dir, received, receivedPath), 1024 * 1024)
                .extract(photo);

        BufferedImage ocrInput = ImageIO.read(received.toFile());
        assertEquals(20, ocrInput.getWidth());
        assertEquals(40, ocrInput.getHeight());
        Path temporary = Path.of(Files.readString(receivedPath, StandardCharsets.UTF_8).strip());
        assertFalse(temporary.equals(photo.toAbsolutePath()), "OCR must receive the rotated copy");
        assertFalse(Files.exists(temporary), "temporary rotated image must be deleted");
        assertEquals(sha256(Files.readAllBytes(photo)), document.sourceSha256());
        assertEquals("texto ocr", document.pages().get(0).lines().get(0).strip());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void passesUnrotatedPhotoToOcrUnchanged(@TempDir Path dir) throws Exception {
        Path photo = dir.resolve("photo.jpg");
        Files.write(photo, jpegWithOrientation(40, 20, 1));
        Path received = dir.resolve("received.png");
        Path receivedPath = dir.resolve("received-path.txt");

        new TesseractImageTextExtractor(fakeTesseract(dir, received, receivedPath), 1024 * 1024)
                .extract(photo);

        assertEquals(
                photo.toAbsolutePath().toString(),
                Files.readString(receivedPath, StandardCharsets.UTF_8).strip());
    }

    private static String fakeTesseract(Path dir, Path received, Path receivedPath) throws Exception {
        Path script = dir.resolve("fake-tesseract.cmd");
        Files.writeString(script, String.join("\r\n",
                "@echo off",
                "copy /y \"%~1\" \"" + received + "\" >nul",
                "echo %~1> \"" + receivedPath + "\"",
                "echo texto ocr",
                ""));
        return script.toString();
    }

    /** Encodes a real JPEG and splices a big-endian EXIF APP1 segment with the given orientation. */
    private static byte[] jpegWithOrientation(int width, int height, int orientation) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", encoded);
        byte[] jpeg = encoded.toByteArray();
        byte[] app1 = {
            (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
            'E', 'x', 'i', 'f', 0x00, 0x00,
            'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(app1);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private Path createCompleteBundle() throws Exception {
        Path bundle = createBundleDirectory();
        Files.writeString(bundle.resolve("tesseract.exe"), "fixture");
        Files.writeString(bundle.resolve("runtime.dll"), "fixture");
        Files.createDirectories(bundle.resolve("tessdata"));
        Files.writeString(bundle.resolve("tessdata/spa.traineddata"), "fixture");
        Files.createDirectories(bundle.resolve("licenses"));
        Files.writeString(bundle.resolve("licenses/THIRD_PARTY_NOTICES.txt"), "fixture");
        return bundle;
    }

    private Path createBundleDirectory() throws Exception {
        Path codeSource = codeSourcePath();
        Path bundle = codeSource.getParent().resolve("ocr");
        assertFalse(Files.exists(bundle), "test fixture must not replace an existing OCR bundle");
        Files.createDirectory(bundle);
        createdBundle = bundle;
        return bundle;
    }

    private void clearDeveloperOverride() {
        previousCommand = System.getProperty(COMMAND_PROPERTY);
        System.clearProperty(COMMAND_PROPERTY);
    }

    private void setDeveloperOverride(String command) {
        previousCommand = System.getProperty(COMMAND_PROPERTY);
        System.setProperty(COMMAND_PROPERTY, command);
    }

    private String configuredCommand() throws Exception {
        Method method = TesseractImageTextExtractor.class.getDeclaredMethod("configuredCommand");
        method.setAccessible(true);
        try {
            return (String) method.invoke(null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof ExtractionException extractionException) {
                throw extractionException;
            }
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> commandLine(TesseractImageTextExtractor extractor) throws Exception {
        Method method = TesseractImageTextExtractor.class.getDeclaredMethod("commandLine", Path.class);
        method.setAccessible(true);
        return (java.util.List<String>) method.invoke(extractor, Path.of("fixture.png"));
    }

    private Path codeSourcePath() throws URISyntaxException {
        return Path.of(TesseractImageTextExtractor.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath();
    }
}
