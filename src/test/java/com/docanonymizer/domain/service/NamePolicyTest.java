package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class NamePolicyTest {
    @Test
    void significantWordsIncludeShortNamesButNeverParticlesOrSingleLetters() {
        assertEquals(List.of("li", "Gil", "Wei", "Paz", "Fernández"),
                NamePolicy.significantTokens("A li de Gil Wei Paz Fernández"));
        for (String token : List.of("A", "de", "van")) {
            assertTrue(NamePolicy.findOccurrences("A a DE De de VAN Van van", token).isEmpty());
        }
    }

    @Test
    void shortOccurrencesRequireCapitalizedOrAllCapsRegardlessOfSeedCase() {
        String text = "paz Paz PAZ pAz xPaz pazX";
        assertEquals(List.of("Paz", "PAZ"), NamePolicy.findOccurrences(text, "paz").stream()
                .map(hit -> text.substring(hit[0], hit[1])).toList());
        assertEquals(3, NamePolicy.findOccurrences("Fernández fernandez FERNÁNDEZ", "fernandez").size());
        assertEquals(1, NamePolicy.findOccurrences("li wang", "Li Wang").size());
    }
}
