package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.service.DetectionEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spreadsheet rows are extracted as tab-separated cells. An IBAN followed by a cell that starts
 * with digits (a payroll period such as "10/2026") must still be detected and replaced.
 */
class IbanCellBoundaryTest {
    // Synthetic IBAN with valid check digits (generated, not a real account).
    private static final String IBAN = "ES46 5136 9032 4113 0200 7477";
    private final DetectionEngine engine = new DetectionEngine(PipelineFactory.defaultDetectors());

    private List<String> ibans(String source) {
        return engine.detect(source).stream()
                .filter(detection -> detection.type().label().equals("IBAN"))
                .map(Detection::value).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t10/2026", "\t1500", " 10/2026", "\n2026"})
    void ibanBeforeANumericCellIsDetected(String next) {
        String source = "EMP-001000\tAna Ruiz Gil\t" + IBAN + next + "\tfin";
        assertEquals(List.of(IBAN), ibans(source), source);
    }

    @Test
    void compactIbanBeforeANumericCellIsDetected() {
        String compact = IBAN.replace(" ", "");
        assertEquals(List.of(compact), ibans(compact + "\t10/2026"));
    }

    @Test
    void ibanFollowedByTextIsStillDetectedOnce() {
        assertEquals(List.of(IBAN), ibans("IBAN " + IBAN + "\tana.ruiz@empresa-ficticia.es"));
    }

    @Test
    void invalidIbanIsStillRejected() {
        assertTrue(ibans("ES46 5136 9032 4113 0200 7478\t10/2026").isEmpty());
    }

    @Test
    void payrollRowIsAnonymizedAndPassesVerification(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("nominas.txt");
        Files.writeString(input, "Nóminas\n\nEMP-001000\tAna Ruiz Gil\t" + IBAN
                + "\t10/2026\t25000\n", StandardCharsets.UTF_8);

        AnonymizationResult result = PipelineFactory.standard().run(input);

        assertTrue(result.verification().passed(), result.verification().toString());
        assertFalse(result.markdown().contains(IBAN), result.markdown());
        assertFalse(result.markdown().contains(IBAN.replace(" ", "")), result.markdown());
        assertTrue(result.markdown().contains("10/2026"), result.markdown());
    }

    /**
     * Workbook rows EMP-001066 / EMP-001103: the IBAN plus the "10" of the next cell's "10/2026"
     * is itself mod-97 valid, so the overrun used to be kept ("[IBAN]/2026").
     */
    @ParameterizedTest
    @ValueSource(strings = {"ES23 2323 5258 4292 6991 3409", "ES23 9642 8438 5256 8865 6012"})
    void ibanWhoseChecksumAlsoHoldsWithTheNextCellsDigitsStopsAtTheTab(String iban) {
        String source = "EMP-001066\tAna Ruiz Gil\t" + iban + "\t10/2026\t45737\n";
        assertEquals(List.of(iban), ibans(source), source);
    }
}
