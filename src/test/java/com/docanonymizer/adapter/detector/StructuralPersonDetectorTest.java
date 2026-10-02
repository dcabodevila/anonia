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

    @Test
    void retainsMixedCaseSurnamesAfterHonorific() {
        assertPerson("D. Juan perez lopez", "Juan perez lopez");
        assertPerson("D. Juan Perez lopez", "Juan Perez lopez");
        assertPerson("D. Juan perez lucio", "Juan perez lucio");
        assertPerson("D. Juan perez aron", "Juan perez aron");
        assertPerson("D. juan perez lucio", "juan perez lucio");
    }

    @Test
    void recognizesUnicodeNamesAfterHonorific() {
        for (String name : List.of("François Dupont", "Łukasz Kowalski", "Søren Kierkegaard",
                "Zoë Smith", "Ştefan Popescu", "Nguyễn Tran", "FRANÇOIS DUPONT",
                "ŁUKASZ KOWALSKI", "SØREN KIERKEGAARD", "ZOË SMITH",
                "ŞTEFAN POPESCU", "NGUYỄN TRAN")) {
            assertPerson("D. " + name, name);
        }
    }

    @Test
    void recognizesConnectedNameWords() {
        for (String name : List.of("Jean-Pierre O'Neal", "Shaquille O’Neal", "D'Angelo Dupont",
                "JEAN-PIERRE O’NEAL")) {
            assertPerson("D. " + name, name);
        }
    }

    @Test
    void recognizesInternalCapitals() {
        for (String name : List.of("Sean McDonald", "Sean MacArthur", "Sean DiCaprio", "Sean LeBlanc")) {
            assertPerson("D. " + name, name);
        }
    }

    @Test
    void recognizesLowercaseUnicodeAndConnectedNamesAfterCue() {
        for (String name : List.of("françois dupont", "łukasz kowalski", "søren kierkegaard",
                "zoë smith", "ştefan popescu", "nguyễn tran", "jean-pierre o’neal", "d'angelo dupont")) {
            assertPerson("D. " + name, name);
        }
    }

    @Test
    void stillRequiresSurnameAfterMariaAbbreviation() {
        assertEquals(List.of(), detector.detect("D. José Mª"));
        assertEquals(List.of(), detector.detect("D. Mª José"));
        assertPerson("D. José Mª Garcia", "José Mª Garcia");
        assertPerson("D. Mª José Garcia", "Mª José Garcia");
    }

    @Test
    void preservesSupplementaryLettersAcrossLineBreakAndInEntityKey() {
        String name = "𐐨𐐩\n𐐪𐐫";
        assertPerson("D. " + name, name);
        assertEquals("PERSON:" + name.replace('\n', ' '), StructuralPersonDetector.entityKey(name));
    }

    private void assertPerson(String source, String expected) {
        List<Detection> detections = detector.detect(source);
        assertEquals(1, detections.size(), source);
        Detection detection = detections.get(0);
        assertEquals(expected, detection.value(), source);
        assertEquals(expected, source.substring(detection.start(), detection.end()), source);
    }
}
