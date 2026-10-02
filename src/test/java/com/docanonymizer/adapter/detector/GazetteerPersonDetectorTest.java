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
        public boolean isSurname(String token) {
            return Set.of("garcia", "perez", "lopez", "ruiz", "santander", "castilla", "carlos", "madrid", "santiago", "de")
                    .contains(CanonicalForm.forCompare(token));
        }

        @Override
        public boolean isExcludedWord(String token) {
            return Set.of("banco", "avenida", "calle", "juzgado", "hospital", "comunidad", "de", "la")
                    .contains(CanonicalForm.forCompare(token));
        }

        @Override
        public int size() {
            return givenNames.size();
        }
    });

    @Test
    void functionWordsAreNotPartOfPersons() {
        for (String connector : List.of("Según", "Segun", "Conforme", "Ante", "Tras")) {
            assertPerson(connector + " García Pérez", "García Pérez");
            assertEquals(List.of(), detector.detect(connector + " Unknown"));
        }
        assertPerson("Segun Sean McDonald", "Sean McDonald");
        assertPerson("Segun GARCÍA", "GARCÍA");
        assertPerson("Sean McDonald Segun García Pérez", "Sean McDonald", "García Pérez");
    }

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

    @Test
    void acceptsSurnameSignalWithoutKnownGivenName() {
        assertPerson("Xiomara García Pérez", "Xiomara García Pérez");
        assertPerson("Brayden López Ruiz", "Brayden López Ruiz");
        assertPerson("Unlisted de García", "Unlisted de García");
        assertEquals(0.80, detector.detect("Xiomara García Pérez").get(0).confidence());
    }

    @Test
    void rejectsExcludedHeadingsAndPostalLocalities() {
        for (String text : List.of("Banco Santander", "Avenida Castilla", "Avenida de Castilla",
                "Calle García Lorca", "Juzgado de Primera Instancia de Santiago",
                "Hospital Clínico San Carlos", "Comunidad de Madrid", "15701 Unlisted García")) {
            assertEquals(List.of(), detector.detect(text), text);
        }
    }

    @Test
    void surnameMustBeLaterAndCapitalized() {
        assertEquals(List.of(), detector.detect("García Unknown"));
        assertEquals(List.of(), detector.detect("Unlisted garcía"));
        assertEquals(List.of(), detector.detect("Unlisted DE"));
        assertEquals(List.of(), detector.detect("UNLISTED UNKNOWN\nRuiz"));
        assertEquals(0.85, detector.detect("Sean McDonald").get(0).confidence());
    }

    private void assertPerson(String source, String first, String second) {
        assertEquals(List.of(first, second), detector.detect(source).stream().map(Detection::value).toList());
    }

    private void assertPerson(String source, String expected) {
        List<Detection> detections = detector.detect(source);
        assertEquals(1, detections.size(), source);
        Detection detection = detections.get(0);
        assertEquals(expected, detection.value(), source);
        assertEquals(expected, source.substring(detection.start(), detection.end()), source);
    }
}
