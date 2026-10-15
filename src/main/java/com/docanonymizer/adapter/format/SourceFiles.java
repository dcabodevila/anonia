package com.docanonymizer.adapter.format;

import com.docanonymizer.domain.port.TextExtractorPort.ExtractionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Helpers shared by extractors; error messages never include document content. */
public final class SourceFiles {

    public static final long DEFAULT_MAX_BYTES = 50L * 1024 * 1024;

    private SourceFiles() {
    }

    /** Reads a regular file no larger than {@code maxBytes}. */
    public static byte[] readBounded(Path source, long maxBytes) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new ExtractionException("La ruta no es un fichero legible.");
        }
        long size = Files.size(source);
        if (size > maxBytes) {
            throw new ExtractionException(
                    "El fichero supera el limite configurado (" + size + " > " + maxBytes + " bytes).");
        }
        return Files.readAllBytes(source);
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static List<String> lines(String text) {
        return Arrays.asList(text.split("\\R", -1));
    }
}
