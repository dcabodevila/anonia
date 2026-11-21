package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactOccurrencesPersonBoundaryTest {
    private static final String ROW = "EMP-001000\tAna\tGarcía\tLópez\tAna García López\tAnalista";

    private static Detection person(String text, String id, String value, String key) {
        int start = text.indexOf(value);
        assertTrue(start >= 0);
        return new Detection(id, DetectionType.PERSON, start, start + value.length(), value, key, Provenance.REGEX, .9);
    }

    private static List<Detection> expand(String text, Detection... seeds) {
        return ExactOccurrences.expand(new AnonymizationPipeline.Analysis(text, List.of(seeds), "hash", 1)).candidates();
    }

    @Test void personSpanNeverCrossesTabOrBlankLine() {
        String text = ROW + "\nAna\nGarcía\n\nAna García";
        var all = expand(text, person(text, "full", "Ana García López", "p"),
                person(text, "pair", "Ana\tGarcía".replace('\t', ' '), "q"));
        for (Detection d : all) {
            assertFalse(d.value().contains("\t"), d.value());
            assertFalse(d.value().contains("\n\n"), d.value());
        }
    }

    @Test void eachNameCellKeepsItsOwnOccurrenceAndFullNameStillMatches() {
        String text = ROW;
        var all = expand(text, person(text, "n", "Ana", "a"), person(text, "s", "García", "g"),
                person(text, "f", "Ana García López", "f"));
        int ana = text.indexOf("	Ana	") + 1;
        int garcia = text.indexOf("	García	") + 1;
        int full = text.indexOf("Ana García López");
        for (int start : new int[]{ana, garcia, full}) {
            assertTrue(all.stream().anyMatch(d -> d.start() == start), "missing span at " + start);
        }
        assertTrue(all.stream().anyMatch(d -> d.start() == full && d.value().equals("Ana García López")));
        assertTrue(all.stream().noneMatch(d -> d.value().contains("	")));
    }

    @Test void firstNameIsNotMatchedInsideAWord() {
        String text = ROW;
        var all = expand(text, person(text, "n", "Ana", "a"));
        for (Detection d : all) assertNotEquals(text.indexOf("Analista"), d.start());
        assertEquals(2, all.stream().filter(d -> d.value().equals("Ana")).count());
    }

    @Test void multiCellValueSeedDoesNotMatchAcrossTabs() {
        String text = "Ana\tGarcía\tx | Ana García";
        var all = expand(text, person(text, "f", "Ana García", "f"));
        assertEquals(1, all.size());
        assertEquals(text.lastIndexOf("Ana"), all.get(0).start());
    }

    @Test void otherTypesStillMatchInsideLongerTextUnchanged() {
        String text = "ref A12 | xA12x";
        var seed = new Detection("c", DetectionType.POSTAL_CODE, 4, 7, "A12", "k", Provenance.REGEX, .9);
        assertEquals(2, expand(text, seed).size());
    }

    @Test void exactIbanResearchDoesNotCrossActualCells() {
        String iban = "ES46 5136 9032 4113 0200 7477";
        String text = iban + "\n\n" + iban.replace("5136 ", "5136\t");
        var seed = new Detection("i", DetectionType.IBAN, 0, iban.length(), iban, "k", Provenance.CHECKSUM_VALIDATED, .99);
        var all = expand(text, seed);
        assertEquals(1, all.size(), all.toString());
    }

    @Test void unicodeWhitespaceOnlyLineIsABlankLine() {
        String text = "Ana García\n\nAna\n\u2009\nGarcía";
        assertEquals(1, expand(text, person(text, "s", "Ana García", "k")).size());
    }

    @Test void combiningMarkDoesNotEndAWord() {
        String text = "Rene\tRene\u0301e";
        assertEquals(1, expand(text, person(text, "s", "Rene", "k")).size());
    }
}
