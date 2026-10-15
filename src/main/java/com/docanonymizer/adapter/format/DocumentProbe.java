package com.docanonymizer.adapter.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

/**
 * What a {@link DocumentFormat} may inspect to recognise a document: the leading bytes, the
 * declared file extension and, when the signature alone is ambiguous, the full content.
 */
public final class DocumentProbe {

    static final int HEADER_BYTES = 8192;

    private final byte[] header;
    private final String extension;
    private final ContentSource content;

    private DocumentProbe(byte[] header, String extension, ContentSource content) {
        this.header = header;
        this.extension = extension;
        this.content = content;
    }

    public static DocumentProbe of(Path source) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(source)) {
            header = input.readNBytes(HEADER_BYTES);
        }
        return new DocumentProbe(header, extensionOf(source.getFileName().toString()),
                () -> Files.newInputStream(source));
    }

    public static DocumentProbe of(byte[] content, String fileName) {
        byte[] copy = content.clone();
        return new DocumentProbe(Arrays.copyOf(copy, Math.min(copy.length, HEADER_BYTES)),
                extensionOf(fileName), () -> new ByteArrayInputStream(copy));
    }

    /** True when the content starts with these unsigned byte values. */
    public boolean startsWith(int... signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((header[i] & 0xff) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /** Lower-case extension including the dot, or an empty string when there is none. */
    public String extension() {
        return extension;
    }

    /** True when the leading bytes contain NUL and are not UTF-16 text with a byte order mark. */
    public boolean looksBinary() {
        if (startsWith(0xff, 0xfe) || startsWith(0xfe, 0xff)) {
            return false;
        }
        for (byte value : header) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    public boolean hasExtension(Collection<String> extensions) {
        return !extension.isEmpty() && extensions.contains(extension);
    }

    public InputStream openContent() throws IOException {
        return content.open();
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface ContentSource {
        InputStream open() throws IOException;
    }
}
