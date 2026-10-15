package com.docanonymizer.adapter.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.zip.ZipFile;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * What a {@link DocumentFormat} may inspect to recognise a document: the leading bytes, the
 * declared file extension and, when the signature alone is ambiguous, the container entries.
 */
public final class DocumentProbe {

    static final int HEADER_BYTES = 8192;

    private final byte[] header;
    private final String extension;
    private final Path path;
    private final byte[] content;

    private DocumentProbe(byte[] header, String extension, Path path, byte[] content) {
        this.header = header;
        this.extension = extension;
        this.path = path;
        this.content = content;
    }

    public static DocumentProbe of(Path source) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(source)) {
            header = input.readNBytes(HEADER_BYTES);
        }
        return new DocumentProbe(header, extensionOf(source.getFileName().toString()), source, null);
    }

    public static DocumentProbe of(byte[] content, String fileName) {
        byte[] copy = content.clone();
        return new DocumentProbe(Arrays.copyOf(copy, Math.min(copy.length, HEADER_BYTES)),
                extensionOf(fileName), null, copy);
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

    /** Lower-case extension including the dot, or an empty string when there is none. */
    public String extension() {
        return extension;
    }

    public boolean hasExtension(Collection<String> extensions) {
        return !extension.isEmpty() && extensions.contains(extension);
    }

    /** True when the content is a readable ZIP archive containing this exact entry name. */
    public boolean containsZipEntry(String name) {
        if (!startsWith(0x50, 0x4b, 0x03, 0x04)) {
            return false;
        }
        try {
            if (path != null) {
                return zipFileContains(path, name);
            }
            Path copy = Files.createTempFile("doc-anonymizer-probe-", ".zip");
            try {
                Files.write(copy, content);
                return zipFileContains(copy, name);
            } finally {
                Files.deleteIfExists(copy);
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Looks the entry up in the central directory only. Streaming the entries would inflate
     * every preceding one, and a small archive can expand to gigabytes (zip bomb).
     */
    private static boolean zipFileContains(Path zip, String name) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            return file.getEntry(name) != null;
        }
    }

    /** True when the content is a readable OLE2 compound file with this root entry. */
    public boolean containsOle2Entry(String name) {
        try (POIFSFileSystem fileSystem = path != null
                ? new POIFSFileSystem(path.toFile(), true)
                : new POIFSFileSystem(new ByteArrayInputStream(content))) {
            return fileSystem.getRoot().hasEntryCaseInsensitive(name);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
