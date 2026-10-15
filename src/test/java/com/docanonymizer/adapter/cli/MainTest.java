package com.docanonymizer.adapter.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test void helpListsEveryRegisteredInputFormat() {
        Output output = run("--help");

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("doc-anonymizer <documento> [opciones]"), output.stdout());
        assertTrue(output.stdout().contains(
                "Formatos admitidos: PDF, JPEG, PNG, Word, Excel, Markdown, TXT."), output.stdout());
        assertEquals("", output.stderr());
    }

    @Test void missingInputNamesADocumentRatherThanAPdf() {
        Output output = run("--dry-run");

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().startsWith("Error: Falta el documento de entrada."), output.stderr());
    }

    private static Output run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = Main.run(args);
            return new Output(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }
}
