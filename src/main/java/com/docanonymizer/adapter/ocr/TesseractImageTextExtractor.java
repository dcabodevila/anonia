package com.docanonymizer.adapter.ocr;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** Local OCR adapter for JPEG and PNG document photos using Tesseract's Spanish model. */
public final class TesseractImageTextExtractor implements TextExtractorPort {

    private static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;
    private static final String COMMAND_PROPERTY = "doc.anonymizer.tesseract.command";

    private final String command;
    private final Path tessdataDirectory;
    private final Path bundledOcrDirectory;
    private final long maxBytes;

    public TesseractImageTextExtractor() {
        this(configuredOcr(), DEFAULT_MAX_BYTES);
    }

    TesseractImageTextExtractor(String command, long maxBytes) {
        this(command, null, null, maxBytes);
    }

    private TesseractImageTextExtractor(OcrConfiguration configuration, long maxBytes) {
        this(
                configuration.command(),
                configuration.tessdataDirectory(),
                configuration.bundledOcrDirectory(),
                maxBytes);
    }

    private TesseractImageTextExtractor(
            String command, Path tessdataDirectory, Path bundledOcrDirectory, long maxBytes) {
        this.command = command;
        this.tessdataDirectory = tessdataDirectory;
        this.bundledOcrDirectory = bundledOcrDirectory;
        this.maxBytes = maxBytes;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        validate(source);
        String text = runTesseract(source);
        return new ExtractedDocument(
                java.util.List.of(new PageText(1, Arrays.asList(text.split("\\R", -1)))),
                sha256(source));
    }

    private void validate(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new ExtractionException("La ruta no es un fichero legible.");
        }
        long size = Files.size(source);
        if (size > maxBytes) {
            throw new ExtractionException("La imagen supera el limite configurado.");
        }
    }

    private String runTesseract(Path source) throws IOException {
        validateBundledOcr();
        Process process;
        try {
            process = new ProcessBuilder(commandLine(source))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new ExtractionException(
                    "OCR local no disponible. Instala Tesseract y el modelo espanol (spa).");
        }

        byte[] output;
        try {
            output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ExtractionException(
                        "No se pudo leer la foto con OCR local. Comprueba Tesseract y el modelo spa.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("OCR local interrumpido.", e);
        }
        return new String(output, StandardCharsets.UTF_8);
    }

    private List<String> commandLine(Path source) {
        List<String> arguments = new java.util.ArrayList<>();
        if (isWindowsCommandScript()) {
            arguments.add("cmd.exe");
            arguments.add("/d");
            arguments.add("/c");
        }
        arguments.add(command);
        arguments.add(source.toAbsolutePath().toString());
        arguments.add("stdout");
        arguments.add("-l");
        arguments.add("spa");
        if (tessdataDirectory != null) {
            arguments.add("--tessdata-dir");
            arguments.add(tessdataDirectory.toString());
        }
        return arguments;
    }

    private boolean isWindowsCommandScript() {
        String normalized = command.toLowerCase(java.util.Locale.ROOT);
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                && (normalized.endsWith(".cmd") || normalized.endsWith(".bat"));
    }

    private static String configuredCommand() {
        return configuredOcr().command();
    }

    private static OcrConfiguration configuredOcr() {
        String configured = System.getProperty(COMMAND_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return new OcrConfiguration(configured, null, null);
        }

        OcrConfiguration bundled = bundledOcrConfiguration();
        if (bundled != null) {
            return bundled;
        }

        configured = System.getenv("TESSERACT_COMMAND");
        return new OcrConfiguration(
                configured == null || configured.isBlank() ? "tesseract" : configured, null, null);
    }

    private static OcrConfiguration bundledOcrConfiguration() {
        Path codeSource = codeSourcePath();
        if (codeSource == null || codeSource.getParent() == null) {
            return null;
        }

        Path ocrDirectory = codeSource.getParent().resolve("ocr");
        if (!Files.exists(ocrDirectory)) {
            return null;
        }
        Path executable = ocrDirectory.resolve("tesseract.exe");
        Path tessdata = ocrDirectory.resolve("tessdata");
        return new OcrConfiguration(executable.toString(), tessdata, ocrDirectory);
    }

    private void validateBundledOcr() {
        if (bundledOcrDirectory == null) {
            return;
        }
        Path executable = bundledOcrDirectory.resolve("tesseract.exe");
        Path spanishModel = bundledOcrDirectory.resolve("tessdata/spa.traineddata");
        Path licenses = bundledOcrDirectory.resolve("licenses");
        if (!Files.isDirectory(bundledOcrDirectory)
                || !Files.isRegularFile(executable)
                || !Files.isRegularFile(spanishModel)
                || !containsFileWithSuffix(bundledOcrDirectory, ".dll")
                || !containsFileNamedWithPrefix(licenses, "third_party_notices")) {
            throw new ExtractionException(
                    "El OCR incluido esta incompleto. Reinstala la aplicacion; no se usara OCR externo.");
        }
    }

    private static Path codeSourcePath() {
        try {
            CodeSource codeSource = TesseractImageTextExtractor.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            return Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean containsFileWithSuffix(Path directory, String suffix) {
        try (var files = Files.list(directory)) {
            return files.anyMatch(file -> Files.isRegularFile(file)
                    && file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(suffix));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsFileNamedWithPrefix(Path directory, String prefix) {
        try (var files = Files.walk(directory)) {
            return files.anyMatch(file -> Files.isRegularFile(file)
                    && file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith(prefix));
        } catch (IOException e) {
            return false;
        }
    }

    private record OcrConfiguration(String command, Path tessdataDirectory, Path bundledOcrDirectory) {}

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
