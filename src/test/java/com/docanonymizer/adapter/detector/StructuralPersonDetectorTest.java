package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.docanonymizer.domain.model.Detection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    static Stream<String> honorifics() {
        return List.of("Sr", "Sra", "Srta", "Don", "Doña", "Dña").stream()
                .flatMap(cue -> Stream.of(cue, cue + "."))
                .flatMap(cue -> Stream.of(cue.toLowerCase(Locale.ROOT), cue,
                        cue.toUpperCase(Locale.ROOT)))
                .distinct();
    }

    @ParameterizedTest
    @MethodSource("honorifics")
    void recognizesHonorificVariantsAndExactSingleWordSpans(String cue) {
        assertPerson("Comparece " + cue + " Kowalski.", "Kowalski");
        assertPerson("Comparece " + cue + " KOWALSKI.", "KOWALSKI");
        assertPerson("Comparece " + cue + " Aiko Tanaka.", "Aiko Tanaka");
    }

    @Test
    void recognizesDottedDAndSingleWordExamples() {
        assertPerson("d. Kowalski", "Kowalski");
        assertPerson("D. Kowalski", "Kowalski");
        assertPerson("el Sr. Kowalski", "Kowalski");
        assertPerson("la Sra Bianchi", "Bianchi");
        assertPerson("Sr Novak", "Novak");
        assertPerson("representado por Sr Novak", "Novak");
    }

    @Test
    void keepsHonorificBoundariesAndSingleWordGuardrails() {
        for (String source : List.of("Sradio Pérez", "1234 BCD. Juan Pérez",
                "un don especial", "D Juan Pérez", "Don De", "Sra LA", "Sr del",
                "Srta especial", "representado por especial", "Novak, con DNI 12345678Z")) {
            assertEquals(List.of(), detector.detect(source), source);
        }
        assertPerson("Srta Novak", "Novak");
        assertPerson("Dña aiko tanaka", "aiko tanaka");
    }

    @Test
    void refinesHonorificSingleWordWithoutChangingOtherCueMinimums() {
        assertPerson("Sr. Kowalski\nCalle Mayor", "Kowalski");
        assertEquals(List.of(), detector.detect("representado por Kowalski\nCalle Mayor"));
    }

    private void assertPerson(String source, String expected) {
        List<Detection> detections = detector.detect(source);
        assertEquals(1, detections.size(), source);
        Detection detection = detections.get(0);
        assertEquals(expected, detection.value(), source);
        assertEquals(source.indexOf(expected), detection.start(), source);
        assertEquals(source.indexOf(expected) + expected.length(), detection.end(), source);
        assertEquals(expected, source.substring(detection.start(), detection.end()), source);
    }
}
