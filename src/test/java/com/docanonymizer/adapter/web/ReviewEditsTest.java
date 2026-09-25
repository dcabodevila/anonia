package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.model.*;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReviewEditsTest {
    private static Detection at(String source, String id, DetectionType type, String value, String key, int from) {
        int start = source.indexOf(value, from);
        assertTrue(start >= 0);
        return new Detection(id, type, start, start + value.length(), value, key, Provenance.REGEX, .9);
    }
    private static AnonymizationPipeline.Analysis analysis(String text, Detection... detections) {
        return new AnonymizationPipeline.Analysis(text, List.of(detections), "hash", 1);
    }
    private static String edit(String id, String value) {
        return "text\t" + id + "\t" + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
    private static List<Detection> apply(AnonymizationPipeline.Analysis input, String... operations) {
        return ReviewEdits.apply(input, "review-v1\n" + String.join("\n", operations));
    }
    private static List<String> ids(List<Detection> detections) {
        return detections.stream().map(Detection::id).toList();
    }

    private static AnonymizationResult completeReview(String... operations) {
        String text = "Calle Maria Garcia | Garcia";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, "Calle", "address", 0),
                at(text, "p", DetectionType.PERSON, "Maria Garcia", "person", 0));
        var pipeline = new AnonymizationPipeline(null, null, null,
                new com.docanonymizer.domain.service.RunScopedIdentifier(), java.time.Clock.systemUTC());
        return pipeline.complete(input, ReviewEdits.replay(input, "review-v1\n" + String.join("\n", operations)));
    }

    @Test void capitalizationVariantsRejectTogetherButTextEditsRemainSourceSpecific() {
        String text = "MARIA GARCIA | Maria Garcia | Maria Garcia Lopez";
        var input = analysis(text,
                at(text, "a", DetectionType.PERSON, "MARIA GARCIA", "person", 0),
                at(text, "b", DetectionType.PERSON, "Maria Garcia", "person", 0),
                at(text, "c", DetectionType.PERSON, "Maria Garcia Lopez", "person", 0));
        assertEquals(List.of("c"), ids(apply(input, "reject\ta\ttrue")));
        var edited = apply(input, edit("a", "GARCIA"));
        assertEquals(List.of("GARCIA", "Maria Garcia", "Maria Garcia Lopez"),
                edited.stream().map(Detection::value).toList());
        assertEquals(edited.get(0).entityKey(), edited.get(1).entityKey());
        assertNotEquals(edited.get(0).entityKey(), edited.get(2).entityKey());
    }

    @Test void reclassifyingAnEntityAsTextoCompletesTheReview() {
        // The review UI offers TEXTO for every entity; the server must accept it end to end.
        var result = completeReview("type	p	TEXTO");
        assertTrue(result.accepted().stream().anyMatch(d -> d.type().label().equals("TEXTO")));
        assertTrue(result.markdown().contains("[TEXTO_001]"), result::markdown);
    }

    @Test void rejectedHiddenPersonDoesNotBlockPipelineExport() {
        String expand = edit("a", "Calle Maria Garcia");
        for (String[] operations : List.of(new String[]{expand, "reject\tp\ttrue"},
                new String[]{"reject\tp\ttrue", expand},
                new String[]{expand, "type\tp\tEMAIL", "reject\tp\ttrue"})) {
            var result = completeReview(operations);
            assertEquals(List.of("a"), ids(result.accepted()));
            assertTrue(result.markdown().contains(" | Garcia"));
            assertTrue(result.deliverable(), () -> result.verification().findings().toString());
        }
    }

    @Test void acceptedHiddenPersonStillBlocksPipelineEvenAfterRelabelingOrRestoringAcceptance() {
        String expand = edit("a", "Calle Maria Garcia");
        for (String[] operations : List.of(new String[]{expand},
                new String[]{expand, "type\tp\tEMAIL"},
                new String[]{expand, "type\tp\tCODIGO"},
                new String[]{expand, "reject\tp\ttrue", "reject\tp\tfalse"},
                new String[]{expand, "type\ta\tEMAIL"})) {
            var result = completeReview(operations);
            assertEquals(List.of("a"), ids(result.accepted()));
            assertTrue(result.verification().blocking().stream()
                    .anyMatch(f -> f.control().equals("C3-TOKENS-DE-NOMBRE")));
            assertFalse(result.deliverable());
        }
    }

    @Test void legacyRejectionMetadataIsValidatedAndCliCompletionRemainsConservative() {
        String text = "Calle Maria Garcia | Garcia";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, "Calle Maria Garcia", "address", 0),
                at(text, "p", DetectionType.PERSON, "Maria Garcia", "person", 0));
        var pipeline = new AnonymizationPipeline(null, null, null,
                new com.docanonymizer.domain.service.RunScopedIdentifier(), java.time.Clock.systemUTC());
        var reviewed = ReviewEdits.replay(input, "p");
        assertEquals(java.util.Set.of("p"), reviewed.rejectedIds());
        assertTrue(pipeline.complete(input, reviewed).deliverable());
        assertFalse(pipeline.complete(input, reviewed.effective()).deliverable());
        assertTrue(ReviewEdits.replay(input, "review-v1\n").rejectedIds().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> ReviewEdits.replay(input, "review-v1\nrejectedIds\tp\ttrue"));
        assertThrows(IllegalArgumentException.class, () -> ReviewEdits.replay(input, "unknown"));
    }

    @Test void narrowsExactContiguousNormalizedSourceSubstringsWithUtf16Offsets() {
        assertArrayEquals(new int[]{7, 13}, ReviewEdits.narrow("Maria  Garcia", "Garcia"));
        assertArrayEquals(new int[]{2, 11}, ReviewEdits.narrow("  Ana\n Ruiz", "Ana Ruiz"));
        assertArrayEquals(new int[]{3, 6}, ReviewEdits.narrow("preAna Garcia", "Ana"));
        assertNull(ReviewEdits.narrow("Maria Garcia Lopez", "Maria Lopez"));
        assertArrayEquals(new int[]{0, 32}, ReviewEdits.narrow(
                "Rúa Estrada de Bueu, 96, Piñeiro,", "Rúa Estrada de Bueu, 96, Piñeiro"));
        assertArrayEquals(new int[]{0, 3}, ReviewEdits.narrow("000/10812,", "000"));
        assertArrayEquals(new int[]{4, 9}, ReviewEdits.narrow("000/10812,", "10812"));
        assertArrayEquals(new int[]{0, 4}, ReviewEdits.narrow("A\n\u00A0B  ", "A B"));
        assertArrayEquals(new int[]{3, 4}, ReviewEdits.narrow("😀 A", "A"));
    }

    @Test void codeFragmentExpansionAnchorsTheOriginalOccurrenceWithoutJumping() {
        String text = "000/10812, | 000/10812,";
        int second = text.indexOf("10812", 5);
        var input = analysis(text,
                at(text, "a", DetectionType.POSTAL_CODE, "10812", "codes", 0),
                at(text, "b", DetectionType.POSTAL_CODE, "10812", "codes", second));
        var expanded = apply(input, edit("a", "000/10812"));
        assertEquals("000/10812", expanded.get(0).value());
        assertEquals(0, expanded.get(0).start());
        assertEquals(text.lastIndexOf("000/10812"), expanded.get(1).start());
        assertEquals("000/10812", expanded.get(1).value());
        var rightExpanded = apply(input, edit("b", "000/10812"));
        assertEquals(0, rightExpanded.get(0).start());
        assertEquals(text.lastIndexOf("000/10812"), rightExpanded.get(1).start());
        assertEquals("000/10812", rightExpanded.get(1).value());
        String elsewhere = "000/10812, | 10812,";
        int elsewhereSecond = elsewhere.indexOf("10812", 5);
        var secondOnly = analysis(elsewhere,
                at(elsewhere, "b", DetectionType.POSTAL_CODE, "10812", "code", elsewhereSecond));
        assertThrows(IllegalArgumentException.class, () -> apply(secondOnly, edit("b", "000/10812")));
    }

    @Test void editsOnlyExplicitOccurrenceAndNeverMergesEqualContentOrPolicy() {
        String text = "Maria  Garcia | Maria Garcia Lopez | Garcia";
        var input = analysis(text,
                at(text, "a", DetectionType.PERSON, "Maria  Garcia", "source", 0),
                at(text, "b", DetectionType.PERSON, "Maria Garcia Lopez", "source", 0),
                at(text, "c", DetectionType.ADDRESS, "Garcia", "destination", 36));
        var result = apply(input, "reject\tc\ttrue", edit("a", "Garcia"));
        assertEquals("Garcia", result.stream().filter(d -> d.id().equals("a")).findFirst().orElseThrow().value());
        assertEquals("Maria Garcia Lopez", result.stream().filter(d -> d.id().equals("b")).findFirst().orElseThrow().value());
        assertNotEquals(result.stream().filter(d -> d.id().equals("a")).findFirst().orElseThrow().entityKey(),
                result.stream().filter(d -> d.id().equals("b")).findFirst().orElseThrow().entityKey());
        assertFalse(ids(apply(input, edit("a", "Garcia"), "reject\ta\ttrue")).contains("a"));
        result = apply(input, edit("a", "Garcia"), "type\ta\tEMAIL");
        assertEquals(DetectionType.EMAIL, result.stream().filter(d -> d.id().equals("a")).findFirst().orElseThrow().type());
        assertEquals(DetectionType.PERSON, result.stream().filter(d -> d.id().equals("b")).findFirst().orElseThrow().type());
        assertFalse(ids(apply(input, "reject\ta\ttrue", edit("a", "Garcia"))).contains("a"));
        assertThrows(IllegalArgumentException.class, () -> apply(input, edit("a", "Maria Garcia Lopez")));
    }

    @Test void expandsRuaAddressRetainsInnerAndRestoresItAfterRejectOrShrink() {
        String full = "Rúa Estrada de Bueu, 96, Piñeiro, Aldán, Cangas do Morrazo";
        String text = "😀 " + full.replace(", Piñeiro", ",\n\u00a0Piñeiro") + " | Piñeiro | Outra rúa";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, "Rúa Estrada de Bueu, 96", "address", 0),
                at(text, "b", DetectionType.PERSON, "Piñeiro", "person", 0),
                at(text, "c", DetectionType.PERSON, "Piñeiro", "person", text.indexOf(" | ")),
                at(text, "d", DetectionType.ADDRESS, "Outra rúa", "address", 0));
        String expand = edit("a", full);
        var result = apply(input, expand);
        assertEquals(List.of("a", "c", "d"), ids(result));
        assertEquals(3, result.get(0).start());
        assertEquals(text.indexOf(" | "), result.get(0).end());
        assertEquals(List.of("b", "c", "d"), ids(apply(input, expand, "reject\ta\ttrue")));
        assertEquals(List.of("a", "b", "c", "d"), ids(apply(input, expand, edit("a", "Bueu,"))));
        assertEquals(result, apply(input, expand, edit("a", "Bueu,"), expand));
        assertEquals(List.of("a", "d"), ids(apply(input, "reject\tb\ttrue", expand)));
        assertEquals(List.of("d"), ids(apply(input, "reject\tb\ttrue", expand, "reject\ta\ttrue")));
        assertEquals(List.of("a", "c", "d"), ids(apply(input, expand, edit("b", "Piñeiro"))));
    }

    @Test void repeatedNumbersInSameGroupStayIndependentOfExpandedOccurrence() {
        String text = "Rúa 96 portal | Rúa 96 portal | 96";
        var input = analysis(text,
                at(text, "a", DetectionType.POSTAL_CODE, "96", "number", 0),
                at(text, "b", DetectionType.POSTAL_CODE, "96", "number", 15),
                at(text, "c", DetectionType.POSTAL_CODE, "96", "other", 29));
        var result = apply(input, edit("a", "Rúa 96 portal"));
        assertEquals(List.of("a", "b", "c"), ids(result));
        assertEquals("Rúa 96 portal", result.get(1).value());
        assertEquals("96", result.get(2).value());
        assertEquals(List.of("c"), ids(apply(input, edit("a", "Rúa 96 portal"), "reject\ta\ttrue")));
    }

    @Test void equalRangesChooseLatestExplicitSelectionOnceAndRetainOtherIdentity() {
        String text = "Calle Norte | Calle Norte";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, "Calle", "one", 0),
                at(text, "b", DetectionType.PERSON, "Norte", "person", 0),
                at(text, "c", DetectionType.EMAIL, "Calle Norte", "two", 12));
        String a = edit("a", "Calle Norte"), b = edit("b", "Calle Norte");
        var result = apply(input, a, b);
        assertEquals(List.of("b", "exact:b:20:25"), ids(result));
        assertTrue(result.stream().allMatch(detection -> detection.type() == DetectionType.PERSON));
        assertEquals("[PERSONA_001] | [PERSONA_001]",
                new com.docanonymizer.domain.service.TextAnonymizer().apply(text, result,
                        java.util.Map.of("person", "[PERSONA_001]", "two", "[EMAIL_001]")));
        assertFalse(ids(apply(input, a, b, "reject\tb\ttrue")).contains("b"));
        var equal = analysis(text,
                at(text, "z", DetectionType.EMAIL, "Calle Norte", "z", 0),
                at(text, "a", DetectionType.ADDRESS, "Calle Norte", "a", 0));
        assertEquals(List.of("z", "exact:z:14:25"), ids(apply(equal)));
    }

    @Test void editsContainedTagWithoutReplacingAcceptedOuter() {
        String text = "Calle Maria Garcia";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, text, "outer", 0),
                at(text, "b", DetectionType.PERSON, "Maria Garcia", "inner", 0));
        assertEquals(List.of("a"), ids(apply(input, edit("b", "Garcia"))));
        var revealed = apply(input, edit("b", "Garcia"), "reject\ta\ttrue");
        assertEquals(List.of("b"), ids(revealed));
        assertEquals("Garcia", revealed.get(0).value());
    }

    @Test void partialOverlapsFailWithActionableErrorWithoutChangingInput() {
        String text = "Calle Norte sector final";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, "Calle", "one", 0),
                at(text, "b", DetectionType.PERSON, "sector final", "two", 0));
        var error = assertThrows(IllegalArgumentException.class, () -> apply(input, edit("a", "Calle Norte sector")));
        assertTrue(error.getMessage().contains("completa"), error.getMessage());
        assertEquals(input.candidates(), apply(input));
    }

    @Test void immutableAnchorsRejectJumpsAndAmbiguousMatches() {
        String text = "Ana | Ana Ruiz | Ana";
        var input = analysis(text, at(text, "a", DetectionType.PERSON, "Ana Ruiz", "one", 0));
        var result = apply(input, edit("a", "Ana"), edit("a", "Ana Ruiz"));
        assertEquals(6, result.get(0).start());
        assertThrows(IllegalArgumentException.class, () -> apply(input, edit("a", "Ana |")));
        String ambiguous = "Ana Ana Ana";
        var repeated = analysis(ambiguous, at(ambiguous, "a", DetectionType.PERSON, "Ana", "one", 4));
        assertThrows(IllegalArgumentException.class, () -> apply(repeated, edit("a", "Ana Ana")));
    }

    @Test void unchosenVariantCannotInvalidateLocalEdit() {
        String text = "Calle Norte sector | Calle Norte sector final";
        var input = analysis(text,
                at(text, "a", DetectionType.ADDRESS, "Calle", "one", 0),
                at(text, "b", DetectionType.ADDRESS, "Calle", "one", 20),
                at(text, "c", DetectionType.PERSON, "sector final", "two", 0));
        var result = apply(input, edit("a", "Calle Norte sector"));
        assertEquals("Calle Norte sector", result.get(0).value());
        assertEquals("Calle", result.get(1).value());
    }

    @Test void generatedSelectionIdentityCannotCollideWithExistingEntity() {
        String text = "96 | 96 | Garcia";
        var input = analysis(text,
                at(text, "a", DetectionType.POSTAL_CODE, "96", "number", 0),
                at(text, "b", DetectionType.POSTAL_CODE, "96", "number", 5),
                at(text, "c", DetectionType.PERSON, "Garcia", "review:a", 0));
        var result = apply(input, edit("a", "96"), "type\ta\tEMAIL", "reject\tc\ttrue");
        assertEquals(List.of("a", "b"), ids(result));
        assertEquals("number", result.get(0).entityKey());
        assertEquals(DetectionType.EMAIL, result.get(0).type());
        assertEquals(DetectionType.EMAIL, result.get(1).type());
    }

    @Test void manualClassificationAcceptsEveryTypeWithoutMergingEditedOccurrences() {
        String text = "Maria Garcia | Maria Garcia | 612345678";
        var input = analysis(text,
                at(text, "a", DetectionType.PERSON, "Maria Garcia", "person", 0),
                at(text, "b", DetectionType.PERSON, "Maria Garcia", "person", 14),
                at(text, "c", DetectionType.PHONE, "612345678", "phone", 0));
        List<String> labels = List.of("DNI", "NIE", "IBAN", "EMAIL", "CLIENTE", "MATRICULA",
                "TELEFONO", "DIRECCION", "CP", "PERSONA", "CODIGO", "TEXTO");

        for (String label : labels) {
            var changed = apply(input, edit("a", "Maria"), "type\ta\t" + label);
            assertEquals(List.of("a", "b", "c"), ids(changed), label);
            assertEquals(label, changed.get(0).type().label(), label);
            assertEquals(label, changed.get(1).type().label(), label);
            assertEquals(DetectionType.PHONE, changed.get(2).type(), label);
            assertEquals("person", changed.get(0).entityKey(), label);
        }

        var coded = apply(input, edit("a", "Maria"), "type\ta\tCODIGO");
        assertEquals("[CODIGO_001] Garcia | [CODIGO_001] Garcia | [TELEFONO_001]",
                new com.docanonymizer.domain.service.TextAnonymizer().apply(text, coded,
                        java.util.Map.of("person", "[CODIGO_001]", "phone", "[TELEFONO_001]")));
        assertEquals(List.of("c"), ids(apply(input, edit("a", "Maria"),
                "type\ta\tCODIGO", "reject\ta\ttrue")));
    }

    @Test void organizationCaseVariantsKeepOneIdentityThroughReviewReplay() {
        String text = "Banco Pastor | BANCO PASTOR";
        var input = analysis(text,
                at(text, "bank", DetectionType.ORGANIZATION, "Banco Pastor", "bank-key", 0),
                at(text, "bank-upper", DetectionType.ORGANIZATION, "BANCO PASTOR", "bank-key", 0));
        var reviewed = ReviewEdits.replay(input, "review-v1\n");
        assertEquals(2, reviewed.effective().size());
        assertEquals(List.of("Banco Pastor", "BANCO PASTOR"),
                reviewed.effective().stream().map(Detection::value).toList());
        assertEquals(1, reviewed.effective().stream().map(Detection::entityKey).distinct().count());
    }

    @Test void expandsAllExactSourceOccurrencesAndEditsTheMatchingBatch() {
        String code = "TRA/2023/36/000/10812";
        String text = code + " | " + code + " | TRA/2023/36/000/108120";
        var input = analysis(text,
                at(text, "a", DetectionType.POSTAL_CODE, "10812", "codes", 0));

        var discovered = apply(input);
        assertEquals(2, discovered.size());
        assertEquals(List.of(16, 40), discovered.stream().map(Detection::start).toList());
        assertTrue(discovered.stream().allMatch(d -> d.value().equals("10812")));

        var edited = apply(input, edit("a", code));
        assertEquals(2, edited.size());
        assertEquals(List.of(code, code), edited.stream().map(Detection::value).toList());
        assertEquals(List.of(0, code.length() + 3), edited.stream().map(Detection::start).toList());
    }

    @Test void addsManualCodigoAtEveryEligibleOccurrenceForApplyAndVerification() {
        String code = "TRA/2023/36/000/10812";
        String text = code + " | " + code + " | TRA/2023/36/000/108120";
        var input = analysis(text);
        var reviewed = ReviewEdits.replay(input, "review-v1\nadd\tmanual:1\t" + code);

        assertEquals(List.of("manual:1", "manual:1:24:45"), ids(reviewed.effective()));
        assertTrue(reviewed.effective().stream().allMatch(detection -> detection.type() == DetectionType.CODIGO));
        var pipeline = new AnonymizationPipeline(null, null, null,
                new com.docanonymizer.domain.service.RunScopedIdentifier(), java.time.Clock.systemUTC());
        var result = pipeline.complete(input, reviewed);
        assertTrue(result.markdown().contains("[CODIGO_001] | [CODIGO_001] | TRA/2023/36/000/108120"));
        assertFalse(result.deliverable(), () -> result.verification().findings().toString());
    }

    @Test void manualEntitiesCanBeRetypedAndRejectedButNeverCreatedWithoutAnEligibleMatch() {
        String text = "REF-1 | REF-1 | REF-12";
        var input = analysis(text);
        String added = "add\tmanual:1\tREF-1";

        var edited = apply(input, added, edit("manual:1", "REF"));
        assertEquals(List.of("REF", "REF"), edited.stream().map(Detection::value).toList());
        var changed = apply(input, added, "type\tmanual:1\tEMAIL");
        assertEquals(List.of(DetectionType.EMAIL, DetectionType.EMAIL),
                changed.stream().map(Detection::type).toList());
        assertTrue(apply(input, added, "reject\tmanual:1\ttrue").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> apply(input, "add\tmanual:2\tAUSENTE"));
    }

    @Test void manualCreationRejectsPartialOverlapsButAcceptsContainedRanges() {
        var input = analysis("ABCDE");
        String abc = "add\tmanual:1\tABC";

        assertThrows(IllegalArgumentException.class,
                () -> apply(input, abc, "add\tmanual:2\tCDE"));
        assertEquals(List.of("manual:2"), ids(apply(input, abc, "add\tmanual:2\tABCDE")));
    }

    @Test void manualCreationRejectsBlankAndWhitespaceSelectionsBeforeMatching() {
        var input = analysis("ABCDE");

        for (String value : List.of("", "%20", "%20%20")) {
            assertThrows(IllegalArgumentException.class,
                    () -> apply(input, "add\tmanual:1\t" + value), value);
        }
    }

    @Test void rejectsUnknownMalformedOrForgedOperationsAndPreservesLegacy() {
        String text = "Maria Garcia";
        var input = analysis(text, at(text, "a", DetectionType.PERSON, text, "one", 0));
        for (String body : List.of("unknown", "review-v1\ntype\ta\tBOGUS", "review-v1\ntext\ta\t",
                "review-v1\ntext\ta\t%GG", "review-v1\nreject\ta\tyes",
                "review-v1\nrange\ta\t0,999", "review-v1\ntype\tmissing\tEMAIL", "review-v1\nbad\tline")) {
            assertThrows(IllegalArgumentException.class, () -> ReviewEdits.apply(input, body), body);
        }
        assertEquals(input.candidates(), apply(input));
        assertTrue(ReviewEdits.apply(input, "a").isEmpty());
    }
}
