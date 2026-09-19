package com.docanonymizer.adapter.ocr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.docanonymizer.domain.port.TextExtractorPort.ExtractionException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
