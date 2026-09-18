package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Unificacion de menciones de la misma persona")
class PersonEntityResolverTest {

    private final PersonEntityResolver resolver = new PersonEntityResolver();

    private static Detection person(String id, String value) {
        return new Detection(id, DetectionType.PERSON, 0, value.length(), value,
                "PERSON:" + CanonicalForm.forCompareKeepingWords(value),
                Provenance.STRUCTURAL_CUE, 0.95);
    }

    @Test
    @DisplayName("la mencion corta se absorbe en el nombre completo")
    void mergesShortMentionIntoFullName() {
        List<Detection> resolved = resolver.resolve(List.of(
                person("a", "Juan Perez Lopez"),
                person("b", "Perez Lopez")));

        assertEquals(resolved.get(0).entityKey(), resolved.get(1).entityKey(),
                "el Sr. Perez Lopez y Juan Perez Lopez son la misma persona");
    }

    @Test
    @DisplayName("no fusiona a dos personas que solo comparten apellidos")
    void keepsDistinctPeopleApart() {
        List<Detection> resolved = resolver.resolve(List.of(
                person("a", "Juan Perez Lopez"),
                person("b", "Marta Perez Lopez")));

        assertNotEquals(resolved.get(0).entityKey(), resolved.get(1).entityKey());
    }

    @Test
    @DisplayName("ante dos candidatos posibles no elige: deja la ambiguedad al revisor")
    void doesNotGuessWhenAmbiguous() {
        // "Perez Lopez" encaja igual de bien con Juan y con Marta. Fusionarla con
        // cualquiera de los dos afirmaria algo que el documento no dice.
        List<Detection> resolved = resolver.resolve(List.of(
                person("a", "Juan Perez Lopez"),
                person("b", "Marta Perez Lopez"),
                person("c", "Perez Lopez")));

        String ambiguous = resolved.get(2).entityKey();
        assertNotEquals(resolved.get(0).entityKey(), ambiguous);
        assertNotEquals(resolved.get(1).entityKey(), ambiguous);
    }

    @Test
    @DisplayName("unifica variantes de caja y acentos del mismo nombre")
    void mergesAcrossCaseAndAccents() {
        List<Detection> resolved = resolver.resolve(List.of(
                person("a", "Andrés Ibáñez Soto"),
                person("b", "IBANEZ SOTO")));

        assertEquals(resolved.get(0).entityKey(), resolved.get(1).entityKey());
    }
}
