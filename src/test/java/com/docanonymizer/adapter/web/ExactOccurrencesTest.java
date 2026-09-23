package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactOccurrencesTest {
    private static Detection detection(String text, String id, DetectionType type, String value, String key, int from) {
        int start = text.indexOf(value, from);
        assertTrue(start >= 0);
        return new Detection(id, type, start, start + value.length(), value, key, Provenance.REGEX, .9);
    }

    @Test void expandsSlashDelimitedExactValuesWithStableIdsButNotLongerDigits() {
        String code = "TRA/2023/36/000/10812";
        String text = code + " | " + code + " | TRA/2023/36/000/108120";
        var analysis = new AnonymizationPipeline.Analysis(text, List.of(
                detection(text, "a", DetectionType.POSTAL_CODE, "10812", "codes", 0)), "hash", 1);

        var expanded = ExactOccurrences.expand(analysis);
        assertEquals(List.of("a", "exact:a:40:45"),
                expanded.candidates().stream().map(Detection::id).toList());
        assertEquals(List.of(16, 40), expanded.candidates().stream().map(Detection::start).toList());
        assertEquals(expanded.candidates(), ExactOccurrences.expand(expanded).candidates());
    }

    @Test void capitalizationVariantsShareReviewIdentityWithoutMergingDifferentNames() {
        String text = "MARIA GARCIA | Maria Garcia | Maria Garcia Lopez";
        var analysis = new AnonymizationPipeline.Analysis(text, List.of(
                detection(text, "a", DetectionType.PERSON, "MARIA GARCIA", "person", 0),
                detection(text, "b", DetectionType.PERSON, "Maria Garcia", "person", 0),
                detection(text, "c", DetectionType.PERSON, "Maria Garcia Lopez", "person", 0)), "hash", 1);
        var expanded = ExactOccurrences.expand(analysis).candidates();
        assertEquals(expanded.get(0).entityKey(), expanded.get(1).entityKey());
        assertNotEquals(expanded.get(0).entityKey(), expanded.get(2).entityKey());
    }

    @Test void preservesAnExplicitDifferentTypeAtTheSameExactRange() {
        String text = "10812 | 10812";
        var analysis = new AnonymizationPipeline.Analysis(text, List.of(
                detection(text, "a", DetectionType.POSTAL_CODE, "10812", "codes", 0),
                detection(text, "b", DetectionType.EMAIL, "10812", "email", 1)), "hash", 1);

        var expanded = ExactOccurrences.expand(analysis);
        assertEquals(List.of("a", "b"), expanded.candidates().stream().map(Detection::id).toList());
        assertEquals(DetectionType.EMAIL, expanded.candidates().get(1).type());
        assertEquals("email", expanded.candidates().get(1).entityKey());
    }
}
