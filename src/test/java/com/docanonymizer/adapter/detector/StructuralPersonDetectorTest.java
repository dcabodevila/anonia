package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.docanonymizer.domain.model.Detection;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralPersonDetectorTest {
    private final StructuralPersonDetector detector = new StructuralPersonDetector();

    @Test
    void recognizesLowercaseNamesAfterStructuralCues() {
        assertPerson("D. juan perez lopez", "juan perez lopez");
        assertPerson("maria garcia, con DNI 12345678Z", "maria garcia");
        assertPerson("representado por luis martin saez", "luis martin saez");
    }

    private void assertPerson(String source, String expected) {
        List<Detection> detections = detector.detect(source);
        assertEquals(1, detections.size(), source);
        Detection detection = detections.get(0);
        assertEquals(expected, detection.value(), source);
        assertEquals(expected, source.substring(detection.start(), detection.end()), source);
    }
}
