package com.docanonymizer.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void routesPngPhotosThroughLocalSpanishOcr() throws Exception {
        Path image = temporaryDirectory.resolve("document.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Path tesseract = createFakeTesseract();
        String previous = System.getProperty("doc.anonymizer.tesseract.command");
        System.setProperty("doc.anonymizer.tesseract.command", tesseract.toString());
        try {
            AnonymizationPipeline.Analysis analysis = PipelineFactory.standard().analyze(image);

            assertEquals("El documento fotografiado contiene texto suficiente para ser analizado.",
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

            assertEquals("El documento fotografiado contiene texto suficiente para ser analizado.",
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

            assertEquals("El documento fotografiado contiene texto suficiente para ser analizado.",
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
