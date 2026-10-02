package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.*;
import com.docanonymizer.domain.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityPropagatorTest {
    @Test
    void sharedSurnamePropagationKeepsFirstSeedOnEqualSpansWithoutMergingPeople() {
        String text = "Juan Perez Lopez. Ana Perez Torres. Perez";
        var first = new Detection("first", DetectionType.PERSON, 0, 16,
                "Juan Perez Lopez", "first-key", Provenance.STRUCTURAL_CUE, .95);
        var second = new Detection("second", DetectionType.PERSON, 18, 34,
                "Ana Perez Torres", "second-key", Provenance.STRUCTURAL_CUE, .95);
        var all = new java.util.ArrayList<>(List.of(first, second));
        all.addAll(new EntityPropagator().propagate(text, List.of(first, second)));
        var resolved = new PersonEntityResolver().resolve(new DetectionEngine(List.of()).resolveOverlaps(all));
        assertEquals("first-key", resolved.get(resolved.size() - 1).entityKey());
        assertNotEquals(resolved.get(0).entityKey(), resolved.get(1).entityKey());
    }

    @Test
    void shortWordsOnlyPropagateInNameCaseAndKeepSeedKey() {
        String text = "Gil GIL gil Wei WEI wei Li LI li Paz PAZ paz FERNÁNDEZ fernandez A";
        var seed = new Detection("seed", DetectionType.PERSON, 0, 1,
                "A Gil Wei Li Paz Fernandez", "person-key", Provenance.STRUCTURAL_CUE, .95);
        var hits = new EntityPropagator().propagate(text, List.of(seed));
        assertEquals(List.of("Gil", "GIL", "Wei", "WEI", "Li", "LI", "Paz", "PAZ", "FERNÁNDEZ", "fernandez"),
                hits.stream().map(Detection::value).toList());
        assertTrue(hits.stream().allMatch(d -> d.entityKey().equals(seed.entityKey())));
    }
}
