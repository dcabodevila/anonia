package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.adapter.ocr.LocalDocumentTextExtractor;
import com.docanonymizer.adapter.review.AutoAcceptReview;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import com.docanonymizer.domain.service.DetectionEngine;
import com.docanonymizer.domain.service.RunScopedIdentifier;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetectionClassificationTest {
    private final DetectionEngine engine = new DetectionEngine(PipelineFactory.defaultDetectors());

    @Test
    void classifiesEveryAutomaticTypeWithValueAndUtf16Offset() {
        String source = "Documento: DNI 12345678Z; NIE X1234567L; IBAN ES9121000418450200051332; "
                + "Email juan.perez@example.com; Número de cliente: ABC12345; Matrícula:1234 BCD; "
                + "Teléfono:612345678; Dirección:Calle Mayor15; Código postal:28013; D. Juan Perez Lopez";
        List<ExpectedDetection> expected = List.of(
                new ExpectedDetection(DetectionType.DNI, "12345678Z", 15),
                new ExpectedDetection(DetectionType.NIE, "X1234567L", 30),
                new ExpectedDetection(DetectionType.IBAN, "ES9121000418450200051332", 46),
                new ExpectedDetection(DetectionType.EMAIL, "juan.perez@example.com", 78),
                new ExpectedDetection(DetectionType.CUSTOMER_ID, "ABC12345", 121),
                new ExpectedDetection(DetectionType.PLATE, "1234 BCD", 141),
                new ExpectedDetection(DetectionType.PHONE, "612345678", 160),
                new ExpectedDetection(DetectionType.ADDRESS, "Calle Mayor15", 181),
                new ExpectedDetection(DetectionType.POSTAL_CODE, "28013", 210),
                new ExpectedDetection(DetectionType.PERSON, "Juan Perez Lopez", 220));

        List<Detection> actual = engine.detect(source);
        assertEquals(expected.size(), actual.size(), actual::toString);
        for (int index = 0; index < expected.size(); index++) {
            ExpectedDetection candidate = expected.get(index);
            Detection detection = actual.get(index);
            assertEquals(candidate.type(), detection.type(), candidate::toString);
            assertEquals(candidate.value(), detection.value(), candidate::toString);
            assertEquals(candidate.start(), detection.start(), candidate::toString);
            assertEquals(candidate.start() + candidate.value().length(), detection.end(), candidate::toString);
        }
    }

    @Test
    void rejectsInvalidOrContextFreeAutomaticLookalikesAndNeverDetectsCodesAutomatically() {
        List<NegativeDetection> negatives = List.of(
                new NegativeDetection("DNI12345678A", DetectionType.DNI),
                new NegativeDetection("DNI12345678Z", DetectionType.DNI),
                new NegativeDetection("NIE X1234567A", DetectionType.NIE),
                new NegativeDetection("IBAN ES9121000418450200051333", DetectionType.IBAN),
                new NegativeDetection("Email juan.perez@example", DetectionType.EMAIL),
                new NegativeDetection("Cliente: ABC12345", DetectionType.CUSTOMER_ID),
                new NegativeDetection("Matrícula:1234 ABC", DetectionType.PLATE),
                new NegativeDetection("Teléfono:512345678", DetectionType.PHONE),
                new NegativeDetection("Mayor 15", DetectionType.ADDRESS),
                new NegativeDetection("28013", DetectionType.POSTAL_CODE),
                new NegativeDetection("D. Juan", DetectionType.PERSON));

        for (NegativeDetection negative : negatives) {
            assertFalse(engine.detect(negative.source()).stream()
                    .anyMatch(detection -> detection.type() == negative.type()), negative::toString);
        }
        assertFalse(engine.detect("Código: ABC12345").stream()
                .anyMatch(detection -> detection.type().name().equals("CODIGO")));
    }

    @Test
    void detectsGivenNamesAndMariaAbbreviationWithSurnamesAsWholePersons() {
        for (String name : List.of("Lola García Pérez", "Ángela Ruiz Molina",
                "Natividad López Díaz", "Mª José García Pérez", "José Mª García Pérez")) {
            String source = "comparece " + name + ".";
            List<Detection> persons = engine.detect(source).stream()
                    .filter(detection -> detection.type() == DetectionType.PERSON).toList();
            assertEquals(List.of(name), persons.stream().map(Detection::value).toList(), source);
            assertEquals(source.indexOf(name), persons.get(0).start(), source);
            assertEquals(source.indexOf(name) + name.length(), persons.get(0).end(), source);
        }
        for (String source : List.of("Lola", "Ángela", "Natividad", "Mª", "José Mª")) {
            assertFalse(engine.detect(source).stream()
                    .anyMatch(detection -> detection.type() == DetectionType.PERSON), source);
        }
    }

    @Test
    void detectsWholeBankNamesAndRendersOrganizationPseudonyms() {
        String source = "Banco Pastor y Banco Popular; Banco Pastoral y Banco Populares; "
                + "XBanco Pastor y Banco PopularX.";
        List<Detection> banks = engine.detect(source).stream()
                .filter(d -> d.type().name().equals("ORGANIZATION")).toList();
        assertEquals(List.of("Banco Pastor", "Banco Popular"),
                banks.stream().map(Detection::value).toList());
        for (Detection bank : banks) {
            assertEquals(source.indexOf(bank.value()), bank.start());
            assertEquals(bank.start() + bank.value().length(), bank.end());
        }
        AnonymizationPipeline pipeline = new AnonymizationPipeline(
                new LocalDocumentTextExtractor(), engine, new AutoAcceptReview(),
                new RunScopedIdentifier(), Clock.systemUTC());
        var analysis = new AnonymizationPipeline.Analysis(source, banks, "test", 1);
        var result = pipeline.complete(analysis, banks);
        org.junit.jupiter.api.Assertions.assertTrue(result.markdown().contains("[ORGANIZACION_001]"));
        org.junit.jupiter.api.Assertions.assertTrue(result.markdown().contains("[ORGANIZACION_002]"));
        org.junit.jupiter.api.Assertions.assertTrue(result.markdown().contains("Banco Pastoral y Banco Populares"));
    }

    @Test
    void detectsBankAcrossLineBreakWithOriginalOffsets() {
        String source = "comparece Banco\nPastor.";
        List<Detection> banks = engine.detect(source).stream()
                .filter(d -> d.type() == DetectionType.ORGANIZATION).toList();
        assertEquals(List.of("Banco\nPastor"), banks.stream().map(Detection::value).toList());
        assertEquals(source.indexOf("Banco"), banks.get(0).start());
        assertEquals(source.indexOf("Pastor") + "Pastor".length(), banks.get(0).end());
    }

    @Test
    void bankSeparatorVariantsShareEntityIdentityWithoutLosingOffsets() {
        String source = "Banco Pastor y Banco\nPastor";
        List<Detection> banks = engine.detect(source).stream()
                .filter(d -> d.type() == DetectionType.ORGANIZATION).toList();
        assertEquals(List.of("Banco Pastor", "Banco\nPastor"),
                banks.stream().map(Detection::value).toList());
        assertEquals(banks.get(0).entityKey(), banks.get(1).entityKey());
        assertEquals(source.indexOf("Banco\nPastor"), banks.get(1).start());
    }

    @Test
    void organizationWinsWhenPersonCandidateOverlapsItsPhrase() {
        String source = "Banco Pastor";
        Detection person = new Detection("person", DetectionType.PERSON, 0, source.length(),
                source, "person", com.docanonymizer.domain.model.Provenance.REGEX, 0.9);
        List<Detection> resolved = new DetectionEngine(List.of(
                new LiteralOrganizationDetector(List.of(source)), new com.docanonymizer.domain.port.DetectorPort() {
                    public String name() { return "person-fixture"; }
                    public List<Detection> detect(String text) { return List.of(person); }
                }))
                .detect(source);
        assertEquals(List.of(DetectionType.ORGANIZATION),
                resolved.stream().map(Detection::type).toList());
    }

    @Test
    void corpusPersonMentionsRetainOneIdentity() {
        String source = "D. Juan Perez\nLopez, mayor de edad, con DNI 12345678Z. "
                + "El Sr. Perez Lopez suscribio. JUAN PEREZ LOPEZ abono.";
        List<Detection> persons = engine.detect(source).stream()
                .filter(d -> d.type() == DetectionType.PERSON).toList();
        assertEquals(List.of("Juan Perez\nLopez", "Perez Lopez", "JUAN PEREZ LOPEZ"),
                persons.stream().map(Detection::value).toList(), persons::toString);
        List<Detection> resolved = new com.docanonymizer.domain.service.PersonEntityResolver()
                .resolve(persons);
        assertEquals(1, resolved.stream().map(Detection::entityKey).distinct().count(),
                resolved::toString);
    }

    private record ExpectedDetection(DetectionType type, String value, int start) { }
    private record NegativeDetection(String source, DetectionType type) { }
}
