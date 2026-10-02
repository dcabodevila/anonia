package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.port.GazetteerPort;
import com.docanonymizer.domain.service.CanonicalForm;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GazetteerPersonDetectorTest {
    private final Set<String> givenNames = Set.of("łukasz", "jeanpierre", "søren", "sean", "dangelo");
    private final GazetteerPersonDetector detector = new GazetteerPersonDetector(new GazetteerPort() {
        @Override
        public boolean isGivenName(String token) {
            return givenNames.contains(CanonicalForm.forCompare(token));
        }

        @Override
        public int size() {
            return givenNames.size();
        }
    });

    @Test
    void recognizesUnicodeGivenNames() {
        assertPerson("Łukasz Kowalski", "Łukasz Kowalski");
        assertPerson("Søren Kierkegaard", "Søren Kierkegaard");
    }

    @Test
    void recognizesConnectedGivenNamesAndSurnames() {
        assertPerson("Jean-Pierre Dupont", "Jean-Pierre Dupont");
        assertPerson("Jean-Pierre O'Neal", "Jean-Pierre O'Neal");
        assertPerson("D’Angelo O’Neal", "D’Angelo O’Neal");
    }

    @Test
    void recognizesAllCapsUnicodeAndConnectedNames() {
        assertPerson("ŁUKASZ KOWALSKI", "ŁUKASZ KOWALSKI");
        assertPerson("SØREN KIERKEGAARD", "SØREN KIERKEGAARD");
        assertPerson("JEAN-PIERRE O’NEAL", "JEAN-PIERRE O’NEAL");
    }

    @Test
    void retainsInternalCapitalsAndLineWrappedNames() {
        assertPerson("Sean McDonald", "Sean McDonald");
        assertPerson("Jean-Pierre\nDupont", "Jean-Pierre\nDupont");
        assertPerson("ŁUKASZ\nKOWALSKI\nContactanos", "ŁUKASZ\nKOWALSKI");
    }

    @Test
    void stillRequiresCapitalizationAndKnownGivenName() {
        assertEquals(List.of(), detector.detect("łukasz kowalski"));
        assertEquals(List.of(), detector.detect("łukasz Kowalski"));
        assertEquals(List.of(), detector.detect("Unknown Kowalski"));
    }

    private void assertPerson(String source, String expected) {
        List<Detection> detections = detector.detect(source);
        assertEquals(1, detections.size(), source);
        Detection detection = detections.get(0);
        assertEquals(expected, detection.value(), source);
        assertEquals(expected, source.substring(detection.start(), detection.end()), source);
    }
}
