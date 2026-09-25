package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextAnonymizerTest {
    @Test
    void preservesOriginalNewlineSequencesAcrossSelectedSpan() {
        String source = "first manifesta-\r\n\rciones last";
        int start = source.indexOf("manifesta-");
        int end = source.indexOf(" last");
        Detection selected = new Detection("id", DetectionType.CODIGO, start, end,
                source.substring(start, end), "key", Provenance.REGEX, 1);
        assertEquals("first [REDACTED]\r\n\r last",
                new TextAnonymizer().apply(source, List.of(selected), Map.of("key", "[REDACTED]")));
    }
}
