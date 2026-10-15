package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.docanonymizer.adapter.gazetteer.ResourceGazetteer;
import com.docanonymizer.domain.model.Detection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Identity-document acronyms are surnames in the shipped dictionary ("Nie"), never a person here. */
class DocumentAcronymPersonTest {
    private final GazetteerPersonDetector detector = new GazetteerPersonDetector(new ResourceGazetteer());

    @ParameterizedTest
    @ValueSource(strings = {"Consta el NIE X1234567L.", "Consta el DNI 12345678Z.",
            "Consta el NIF 12345678Z.", "Consta el CIF B12345678."})
    void documentAcronymAfterAParticleIsNotAPerson(String text) {
        assertEquals(List.of(), detector.detect(text).stream().map(Detection::value).toList(), text);
    }

    @Test
    void givenNameStillCarriesTheSameSurname() {
        assertEquals(List.of("Wei Nie"),
                detector.detect("el contrato lo firma Wei Nie.").stream().map(Detection::value).toList());
    }
}
