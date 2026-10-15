package com.docanonymizer.adapter.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.tools.SampleDocumentGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the CLI in a fresh JVM: logging bootstrap noise only shows up once per process. */
class CliOutputTest {

    @TempDir Path temp;

    @Test void pdfDryRunPrintsOnlyTheSummary() throws Exception {
        Path pdf = temp.resolve("acta.pdf");
        SampleDocumentGenerator.generate(pdf);

        assertCleanDryRun(pdf);
    }

    @Test void legacyWordDryRunPrintsOnlyTheSummary() throws Exception {
        Path doc = Files.copy(Path.of("src/test/resources/formats/sample.doc"), temp.resolve("acta.doc"));

        assertCleanDryRun(doc);
    }

    private static void assertCleanDryRun(Path input) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Ddoc.anonymizer.rules=" + Path.of("src/test/resources/isolated-rules.txt").toAbsolutePath(),
                "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), input.toString(), "--dry-run")
                .start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), stdout + stderr);
        assertFalse(stdout.contains("Log4j"), stdout);
        assertTrue(stdout.startsWith("Paginas procesadas"), stdout);
        assertEquals("", stderr);
    }
}
