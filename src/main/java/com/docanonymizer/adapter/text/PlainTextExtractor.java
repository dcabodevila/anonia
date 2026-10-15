package com.docanonymizer.adapter.text;

import com.docanonymizer.adapter.format.SourceFiles;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads plain text and Markdown as a single page, keeping the source lines unchanged.
 *
 * <p>Encoding: a byte order mark decides when present; otherwise strict UTF-8, falling back
 * to windows-1252, the usual encoding of legacy Spanish Windows text files.
 */
public final class PlainTextExtractor implements TextExtractorPort {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private final long maxBytes;

    public PlainTextExtractor() {
        this(SourceFiles.DEFAULT_MAX_BYTES);
    }

    public PlainTextExtractor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public ExtractedDocument extract(Path source) throws IOException {
        byte[] content = SourceFiles.readBounded(source, maxBytes);
        String text = decode(content);
        return new ExtractedDocument(List.of(new PageText(1, SourceFiles.lines(text))),
                SourceFiles.sha256(content));
    }

    static String decode(byte[] content) {
        if (startsWith(content, 0xef, 0xbb, 0xbf)) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(content, 0xff, 0xfe)) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(content, 0xfe, 0xff)) {
            return new String(content, 2, content.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, WINDOWS_1252);
        }
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xff) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
