package com.docanonymizer.adapter.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * What a {@link DocumentFormat} may inspect to recognise a document: the leading bytes, the
 * declared file extension and, when the signature alone is ambiguous, the container entries.
 */
public final class DocumentProbe {

    static final int HEADER_BYTES = 8192;
    private static final int MAX_ZIP_ENTRIES = 10_000;

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
        try (ZipInputStream zip = new ZipInputStream(openContent())) {
            ZipEntry entry;
            for (int seen = 0; seen < MAX_ZIP_ENTRIES && (entry = zip.getNextEntry()) != null; seen++) {
                if (entry.getName().equals(name)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
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

    InputStream openContent() throws IOException {
        return path != null ? Files.newInputStream(path) : new ByteArrayInputStream(content);
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
