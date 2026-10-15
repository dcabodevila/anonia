package com.docanonymizer.adapter.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The static picker must offer exactly the registered formats: registering one updates both. */
class PickerFormatsTest {

    @Test void filePickerAcceptsExactlyTheRegisteredFormats() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/web/index.html"));
        Matcher accept = Pattern.compile("<input type=\"file\" id=\"file\" accept=\"([^\"]*)\"").matcher(html);

        assertTrue(accept.find(), "file input not found");
        assertEquals(DocumentFormatRegistry.standard().acceptAttribute(), accept.group(1));
    }
}
