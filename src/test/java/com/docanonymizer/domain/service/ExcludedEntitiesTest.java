package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExcludedEntitiesTest {
    @Test void excludedPhrasePreservesWordBoundaries() {
        ExcludedEntities exclusions = new ExcludedEntities(List.of(), List.of("Banco Pastor"), List.of("A B"));
        Detection spacedOrganization = detection("Banco Pastor", DetectionType.ORGANIZATION);
        Detection unspacedOrganization = detection("BancoPastor", DetectionType.ORGANIZATION);
        Detection spacedTerm = detection("A B", DetectionType.TERM);
        Detection unspacedTerm = detection("AB", DetectionType.TERM);

        assertEquals(List.of(unspacedOrganization, unspacedTerm), exclusions.filter(List.of(
                spacedOrganization, unspacedOrganization, spacedTerm, unspacedTerm)));
    }

    @Test void excludedPhraseStillToleratesCaseAccentsAndVariableWhitespace() {
        ExcludedEntities exclusions = new ExcludedEntities(List.of(), List.of("Bánco Pastor"), List.of("A B"));
        assertEquals(List.of(), exclusions.filter(List.of(
                detection("BANCO   PASTOR", DetectionType.ORGANIZATION),
                detection("a\t b", DetectionType.TERM))));
    }

    private static Detection detection(String value, DetectionType type) {
        return new Detection(value, type, 0, value.length(), value, value, Provenance.MANUAL, 1.0);
    }
}
