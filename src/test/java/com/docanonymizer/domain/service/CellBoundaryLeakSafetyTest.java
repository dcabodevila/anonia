package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.*;

import com.docanonymizer.domain.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Propagation stops at cell and row boundaries, but the leak gate must still see across them. */
class CellBoundaryLeakSafetyTest {

    @Test
    void boundaryLeaksAreStillBlockedAndWrapsStillPropagate() {
        String name = "Juan Perez Lopez";
        var seed = new Detection("seed", DetectionType.PERSON, 0, name.length(),
                name, "key", Provenance.STRUCTURAL_CUE, .95);
        var verifier = new RedactionVerifier(new RunScopedIdentifier());
        var labels = Map.of("key", "[PERSONA_001]");
        assertTrue(verifier.verify("[PERSONA_001]", List.of(seed), labels).passed());

        for (String gap : List.of("\t", " \t ", "\n\n", "\n \n", "\n\n\n")) {
            String leak = "Juan Perez" + gap + "Lopez";
            assertFalse(NamePolicy.findOccurrences(leak, name).isEmpty());
            var report = verifier.verify("[PERSONA_001] " + leak, List.of(seed), labels);
            assertFalse(report.passed());
            assertTrue(report.blocking().stream().anyMatch(f -> f.control().equals("C1-VALOR-LITERAL")));
        }

        for (String gap : List.of(" ", "\n", " \n ")) {
            String repeat = "Juan Perez" + gap + "Lopez";
            assertTrue(new EntityPropagator().propagate(name + ". " + repeat, List.of(seed))
                    .stream().anyMatch(d -> d.value().equals(repeat)));
        }

        assertTrue(TextFolding.findWholeWordOccurrences("", name).isEmpty());
        assertTrue(TextFolding.findWholeWordOccurrences(name, "").isEmpty());
    }
}
