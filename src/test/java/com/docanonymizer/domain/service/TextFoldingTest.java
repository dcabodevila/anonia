package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextFoldingTest {

    @Test
    void firstHitMatchesTheFirstOfAllOccurrencesOnRandomText() {
        String[] atoms = {"Ana", "garcía", "Garcia", "ana", "Lopez", " ", " ", "\n", "\t", "x", "1", "-"};
        List<String> needles = List.of("Ana", "Ana Garcia", "garcia lopez", "Lopez", "x", "Ana  Garcia Lopez", "zz");
        Random random = new Random(42);
        for (int round = 0; round < 500; round++) {
            StringBuilder text = new StringBuilder();
            for (int i = random.nextInt(40); i > 0; i--) {
                text.append(atoms[random.nextInt(atoms.length)]);
            }
            for (String needle : needles) {
                List<int[]> all = TextFolding.findWholeWordOccurrences(text.toString(), needle);
                int[] first = TextFolding.findFirstWholeWordOccurrence(
                        new StringBuilder(TextFolding.fold(text.toString())), needle);
                if (all.isEmpty()) {
                    assertNull(first, needle + " in " + text);
                } else {
                    assertArrayEquals(all.get(0), first, needle + " in " + text);
                }
            }
        }
    }

    @Test
    void firstHitOfBlankNeedleIsNull() {
        assertNull(TextFolding.findFirstWholeWordOccurrence(new StringBuilder("abc"), "  "));
    }

    @Test
    void firstHitSkipsNonWholeMatchesAndCanCrossCellSeparators() {
        String text = "xANA García | Ána\tGarcía2 | (Ána\n\n\tGarcía)";
        String needle = "  Ana  Garcia ";
        int[] expected = {text.indexOf('(') + 1, text.indexOf(')')};
        assertArrayEquals(expected, TextFolding.findWholeWordOccurrences(text, needle).get(0));
        assertArrayEquals(expected, TextFolding.findFirstWholeWordOccurrence(new StringBuilder(TextFolding.fold(text)), needle));
        assertTrue(TextFolding.findWholeWordOccurrencesWithinCell(text, needle).isEmpty());
    }
}
