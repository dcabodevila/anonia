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
    void stopsCueNamesBeforeLowercaseProse() {
        assertPerson("D. François Dupont para ratificar el escrito", "François Dupont");
        assertPerson("el Sr. Kowalski para ratificar la demanda", "Kowalski");
        assertPerson("Sr Novak que recibió la citación", "Novak");
        assertPerson("D. Sean McDonald en calidad de testigo", "Sean McDonald");
        assertPerson("representado por luis martin saez para ratificar el escrito", "luis martin saez");
        assertPerson("D. Juan Perez de", "Juan Perez");
        assertPerson("D. Juan Perez de\nCalle Mayor", "Juan Perez");
        assertPerson("D. Juan de la Cruz para ratificar el escrito", "Juan de la Cruz");
        assertPerson("D. juan perez lopez para ratificar el escrito", "juan perez lopez");
        assertPerson("D. FRANÇOIS DUPONT para ratificar el escrito", "FRANÇOIS DUPONT");
        assertPerson("D. Juan Perez desconocido Garcia", "Juan Perez");
        assertEquals(List.of(), detector.detect("D. desconocido inventado"));
    }

    @Test
    void existingLowercaseFixturesAreKnownNames() {
        var gazetteer = new com.docanonymizer.adapter.gazetteer.ResourceGazetteer();
        org.junit.jupiter.api.Assertions.assertAll(
                Stream.of("juan", "perez", "lopez", "lucio", "aron", "maria", "garcia",
                        "luis", "martin", "saez", "françois", "dupont", "łukasz", "kowalski",
                        "søren", "zoë", "smith", "ştefan", "popescu",
                        "nguyễn", "tran", "jean-pierre", "o’neal", "d'angelo", "aiko", "tanaka")
                        .map(word -> () -> org.junit.jupiter.api.Assertions.assertTrue(
                                gazetteer.isGivenName(word) || gazetteer.isSurname(word),
                                () -> "lowercase fixture absent from gazetteer: " + word)));
    }

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
            assertPerson(unicodeDetector(), "D. " + name, name);
        }
    }

    private StructuralPersonDetector unicodeDetector() {
        var defaults = new com.docanonymizer.adapter.gazetteer.ResourceGazetteer();
        return new StructuralPersonDetector(new com.docanonymizer.domain.port.GazetteerPort() {
            @Override public boolean isGivenName(String token) {
                return defaults.isGivenName(token)
                        || List.of("kierkegaard", "𐐨𐐩", "𐐪𐐫").contains(token);
            }
            @Override public boolean isSurname(String token) { return defaults.isSurname(token); }
            @Override public int size() { return defaults.size() + 3; }
        });
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
        assertPerson(unicodeDetector(), "D. " + name, name);
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
        assertPerson(detector, source, expected);
    }

    private void assertPerson(StructuralPersonDetector subject, String source, String expected) {
        List<Detection> detections = subject.detect(source);
        assertEquals(1, detections.size(), source);
        Detection detection = detections.get(0);
        assertEquals(expected, detection.value(), source);
        assertEquals(source.indexOf(expected), detection.start(), source);
        assertEquals(source.indexOf(expected) + expected.length(), detection.end(), source);
        assertEquals(expected, source.substring(detection.start(), detection.end()), source);
    }
}
