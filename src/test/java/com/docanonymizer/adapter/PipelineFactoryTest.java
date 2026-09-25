package com.docanonymizer.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.service.DetectionEngine;

import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class PipelineFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exclusionsAreTypedCaseInsensitiveAndSnapshotScoped() throws Exception {
        Path rules = temporaryDirectory.resolve("rules.txt");
        Files.writeString(rules, "person: María\nterm: Oposición\n"
                + "exclude-person: María García Pérez\nexclude-organization: BANCO PASTOR\n"
                + "exclude-term: oposición\n");
        String previous = System.getProperty("doc.anonymizer.rules");
        String text = "María García Pérez compareció ante el tribunal. García Pérez declaró después. "
                + "María López declaró también ante Banco Pastor y Banco Popular sobre la Oposición. "
                + "El expediente incluye nombres y organizaciones suficientes para analizar este documento.";
        try {
            System.setProperty("doc.anonymizer.rules", rules.toString());
            var configured = PipelineFactory.withReview((source, candidates) -> candidates,
                    java.time.Clock.systemUTC());
            Files.writeString(rules, "# no exclusions\nperson: María\nterm: Oposición\n");
            var candidates = configuredWithText(configured, text);
            assertTrue(candidates.stream().noneMatch(d -> d.type() == DetectionType.PERSON
                    && (d.value().equalsIgnoreCase("María García Pérez")
                    || d.value().equalsIgnoreCase("García Pérez"))), () -> "Excluded person: " + candidates);
            assertTrue(candidates.stream().anyMatch(d -> d.type() == DetectionType.PERSON
                    && d.value().equals("María López")));
            assertTrue(candidates.stream().noneMatch(d -> d.type() == DetectionType.ORGANIZATION
                    && d.value().equalsIgnoreCase("Banco Pastor")));
            assertTrue(candidates.stream().anyMatch(d -> d.type() == DetectionType.ORGANIZATION
                    && d.value().equals("Banco Popular")));
            assertTrue(candidates.stream().noneMatch(d -> d.type() == DetectionType.TERM));
            assertTrue(configuredWithText(PipelineFactory.withReview((source, found) -> found,
                    java.time.Clock.systemUTC()), text).stream().anyMatch(d -> d.type() == DetectionType.TERM));
        } finally {
            if (previous == null) System.clearProperty("doc.anonymizer.rules");
            else System.setProperty("doc.anonymizer.rules", previous);
        }
    }

    private java.util.List<com.docanonymizer.domain.model.Detection> configuredWithText(
            AnonymizationPipeline pipeline, String text) throws Exception {
        // Exercise the factory's pipeline with a synthetic document via its detector snapshot.
        return pipeline.analyze(writeSyntheticPdf(text)).candidates();
    }

    private Path writeSyntheticPdf(String text) throws Exception {
        Path pdf = temporaryDirectory.resolve("synthetic.pdf");
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void customTermIsOptInAndPreservesExactSpan() throws Exception {
        Path rules = temporaryDirectory.resolve("rules.txt");
        Files.writeString(rules, "term: Oposición\n");
        String previous = System.getProperty("doc.anonymizer.rules");
        try {
            System.setProperty("doc.anonymizer.rules", rules.toString());
            String text = "La Oposición y preOposición";
            var found = new DetectionEngine(PipelineFactory.defaultDetectors()).detect(text);
            assertTrue(found.stream().anyMatch(d -> d.type() == DetectionType.TERM
                    && d.value().equals("Oposición") && d.start() == 3 && d.end() == 12));
            assertTrue(found.stream().noneMatch(d -> d.value().equals("preOposición")));
        } finally {
            if (previous == null) System.clearProperty("doc.anonymizer.rules");
            else System.setProperty("doc.anonymizer.rules", previous);
        }
    }

    @Test
    void legalWordsAreNotDefaultEntityCandidates() throws Exception {
        Path rules = temporaryDirectory.resolve("empty-rules.txt");
        Files.writeString(rules, "");
        String previous = System.getProperty("doc.anonymizer.rules");
        try {
            System.setProperty("doc.anonymizer.rules", rules.toString());
            String text = "Oposición oposición INSTANCIA Instancia primera instancia Audiencia Juzgado Fecha";
            var found = new DetectionEngine(PipelineFactory.defaultDetectors()).detect(text);
            assertTrue(found.isEmpty(), () -> "Unexpected default candidates: " + found);
        } finally {
            if (previous == null) System.clearProperty("doc.anonymizer.rules");
            else System.setProperty("doc.anonymizer.rules", previous);
        }
    }

    @Test
    void customRulesAreAdditiveAndIsolatedBetweenPipelines() throws Exception {
        Path rules = temporaryDirectory.resolve("rules.txt");
        Files.writeString(rules, "person: Íñigo\norganization: Banco Santander\n"
                + "term: Oposición\nterm: INSTANCIA\nterm: primera\nterm: Audiencia\n"
                + "term: Juzgado\nterm: Fecha\n");
        String previous = System.getProperty("doc.anonymizer.rules");
        String text = "Íñigo García Pérez; Banco Santander; Banco Pastor; "
                + "Oposición INSTANCIA primera Audiencia Juzgado Fecha; Santanderismo";
        try {
            System.setProperty("doc.anonymizer.rules", rules.toString());
            var configured = new DetectionEngine(PipelineFactory.defaultDetectors());
            Files.writeString(rules, "# changed after construction\n");
            var found = configured.detect(text);
            assertTrue(found.stream().anyMatch(d -> d.type() == DetectionType.PERSON
                    && d.value().equals("Íñigo García Pérez")));
            assertTrue(found.stream().anyMatch(d -> d.type() == DetectionType.ORGANIZATION
                    && d.value().equals("Banco Santander")));
            assertTrue(found.stream().anyMatch(d -> d.type() == DetectionType.ORGANIZATION
                    && d.value().equals("Banco Pastor")));
            for (String term : java.util.List.of("Oposición", "INSTANCIA", "primera", "Audiencia", "Juzgado", "Fecha")) {
                assertTrue(found.stream().anyMatch(d -> d.type() == DetectionType.TERM
                        && d.value().equals(term)), term);
            }
            assertTrue(new DetectionEngine(PipelineFactory.defaultDetectors()).detect(text).stream()
                    .noneMatch(d -> d.type() == DetectionType.TERM
                            || d.value().equals("Banco Santander")));
        } finally {
            if (previous == null) System.clearProperty("doc.anonymizer.rules");
            else System.setProperty("doc.anonymizer.rules", previous);
        }
    }

    @Test
    void routesPngPhotosThroughLocalSpanishOcr() throws Exception {
        Path image = temporaryDirectory.resolve("document.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Path tesseract = createFakeTesseract();
        String previous = System.getProperty("doc.anonymizer.tesseract.command");
        System.setProperty("doc.anonymizer.tesseract.command", tesseract.toString());
        try {
            AnonymizationPipeline.Analysis analysis = PipelineFactory.standard().analyze(image);

            assertEquals("El documento fotografiado contiene texto suficiente para ser analizado.\n",
                    analysis.normalizedText());
            assertEquals(1, analysis.pageCount());
        } finally {
            if (previous == null) {
                System.clearProperty("doc.anonymizer.tesseract.command");
            } else {
                System.setProperty("doc.anonymizer.tesseract.command", previous);
            }
        }
    }

    @Test
    void routesJpegPhotosUsingTheSpanishOcrModel() throws Exception {
        Path image = temporaryDirectory.resolve("document.jpg");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpg", image.toFile());
        Path tesseract = createFakeTesseract();
        String previous = System.getProperty("doc.anonymizer.tesseract.command");
        System.setProperty("doc.anonymizer.tesseract.command", tesseract.toString());
        try {
            AnonymizationPipeline.Analysis analysis = PipelineFactory.standard().analyze(image);

            assertEquals("El documento fotografiado contiene texto suficiente para ser analizado.\n",
                    analysis.normalizedText());
        } finally {
            if (previous == null) {
                System.clearProperty("doc.anonymizer.tesseract.command");
            } else {
                System.setProperty("doc.anonymizer.tesseract.command", previous);
            }
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    void runsWindowsBatchLaunchersConfiguredForTesseract() throws Exception {
        Path image = temporaryDirectory.resolve("document.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Path tesseract = createFakeTesseract(temporaryDirectory, "tesseract.bat", true);
        String previous = System.getProperty("doc.anonymizer.tesseract.command");
        System.setProperty("doc.anonymizer.tesseract.command", tesseract.toString());
        try {
            AnonymizationPipeline.Analysis analysis = PipelineFactory.standard().analyze(image);

            assertEquals("El documento fotografiado contiene texto suficiente para ser analizado.\n",
                    analysis.normalizedText());
        } finally {
            if (previous == null) {
                System.clearProperty("doc.anonymizer.tesseract.command");
            } else {
                System.setProperty("doc.anonymizer.tesseract.command", previous);
            }
        }
    }

    private Path createFakeTesseract() throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return createFakeTesseract(windows ? "tesseract.cmd" : "tesseract");
    }

    private Path createFakeTesseract(String launcherName) throws IOException {
        return createFakeTesseract(temporaryDirectory, launcherName);
    }

    private Path createFakeTesseract(Path directory, String launcherName) throws IOException {
        return createFakeTesseract(directory, launcherName, false);
    }

    private Path createFakeTesseract(Path directory, String launcherName, boolean requireCommandInterpreter) throws IOException {
        String text = "El documento fotografiado contiene texto suficiente para ser analizado.";
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path executable = directory.resolve(launcherName);
        String script = windows
                ? "@echo off\r\n"
                        + (requireCommandInterpreter
                                ? "echo %CMDCMDLINE% | findstr /I /C:\"/d\" >nul || exit /b 4\r\n"
                                : "")
                        + "if /I not \"%4\"==\"spa\" exit /b 3\r\necho " + text + "\r\n"
                : "#!/bin/sh\n[ \"$4\" = \"spa\" ] || exit 3\nprintf '%s\\n' '" + text + "'\n";
        Files.writeString(executable, script);
        executable.toFile().setExecutable(true);
        return executable;
    }
}
