package com.docanonymizer.adapter.web;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DesktopLauncherTest {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    @Test void startsLoopbackBeforeOpeningBrowser() {
        List<String> events = new ArrayList<>();
        assertEquals(0, DesktopLauncher.launch(new String[0],
                (host, port) -> events.add(host + ":" + port),
                uri -> events.add(uri.toString()), out));
        assertEquals(List.of("127.0.0.1:8080", "http://127.0.0.1:8080/"), events);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Ctrl+C"));
    }

    @Test void startupFailureNeverOpensBrowser() {
        List<String> opened = new ArrayList<>();
        assertEquals(1, DesktopLauncher.launch(new String[0],
                (host, port) -> { throw new java.net.BindException("busy"); },
                uri -> opened.add(uri.toString()), out));
        assertTrue(opened.isEmpty());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("8080"));
    }

    @Test void browserFailureLeavesServerAvailableAndPrintsUrl() {
        assertEquals(0, DesktopLauncher.launch(new String[]{"9090"}, (host, port) -> {
            assertEquals("127.0.0.1", host);
            assertEquals(9090, port);
        }, uri -> { throw new UnsupportedOperationException(); }, out));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("http://127.0.0.1:9090/"));
    }

    @Test void rejectsInvalidPortsBeforeAnySideEffects() {
        for (String[] args : List.of(new String[]{"0"}, new String[]{"65536"},
                new String[]{"abc"}, new String[]{"8080", "extra"})) {
            assertEquals(1, DesktopLauncher.launch(args,
                    (host, port) -> fail("server started"), uri -> fail("browser opened"), out));
        }
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @Test void packagingPlanUsesIsolatedJarInputBundledRuntimeAndDesktopEntrypoint() throws Exception {
        Path script = Path.of("packaging/windows/build-installer.ps1");
        assertTrue(Files.exists(script), "packaging script must exist");
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Plan")
                .redirectErrorStream(true).start();
        String plan = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), plan);
        assertTrue(plan.contains("0.3.1"), "installer version missing: " + plan);
        for (String expected : List.of("--type", "exe", "--win-per-user-install", "--win-menu",
                "--win-shortcut", "--win-console", "--main-class",
                "com.docanonymizer.adapter.web.DesktopLauncher", "doc-anonymizer.jar",
                "windows-installer", "windows-input-", "--add-modules", "ALL-MODULE-PATH")) {
            assertTrue(plan.contains(expected), expected + " missing: " + plan);
        }
        assertFalse(plan.contains("--runtime-image"), "jpackage must create bundled runtime");
        assertTrue(plan.contains("--resource-dir"), "jpackage resource directory missing: " + plan);
        assertTrue(plan.contains("jpackage-resources"), "jpackage resource directory missing: " + plan);
        assertTrue(plan.contains("ce0936d4-f914-4f47-9052-5b286df59f57"));
        Path mainWxs = Path.of("packaging/windows/jpackage-resources/main.wxs");
        assertTrue(Files.isRegularFile(mainWxs), "WiX main.wxs override must exist");
        String wix = Files.readString(mainWxs);
        String tesseractCommand = "C:\\Program Files\\Tesseract-OCR\\tesseract.exe";
        assertTrue(wix.matches("(?s).*<Directory Id=\"TARGETDIR\" Name=\"SourceDir\">\\s*"
                + "<Component Id=\"TesseractCommandEnvironment\".*"),
                "Tesseract environment component must be nested under TARGETDIR");
        assertTrue(wix.contains("<ComponentRef Id=\"TesseractCommandEnvironment\"/>"));
        assertTrue(wix.contains("<Environment Id=\"TesseractCommand\""));
        assertFalse(wix.contains("<util:Environment"));
        assertTrue(wix.contains("Name=\"TESSERACT_COMMAND\""));
        assertTrue(wix.contains("Value=\"" + tesseractCommand + "\""));
        assertTrue(wix.contains("Action=\"set\""));
        assertTrue(wix.contains("System=\"no\""));
        assertTrue(wix.contains("Permanent=\"no\""));
        assertFalse(wix.contains("Name=\"PATH\""));
        String source = Files.readString(script);
        assertTrue(source.contains("mvn -o package"));
        assertTrue(source.contains("Copy-Item -LiteralPath $jar -Destination $inputDirectory"));
        assertFalse(source.contains("OcrBundleRoot"));
        assertFalse(source.contains("Stage-OcrBundle"));
        assertFalse(source.contains("bundle-manifest.json"));
        assertFalse(source.contains("thirdPartyLicenseEvidence"));
        assertFalse(source.contains("tessdata/spa.traineddata"));
        assertFalse(source.contains("THIRD_PARTY_NOTICES"));
        assertFalse(source.contains("Get-Command tesseract.exe"));
        assertTrue(source.contains("WiX Toolset v3*"));
        assertTrue(source.contains("$env:PATH = $originalPath"));
        assertFalse(source.contains("SetEnvironmentVariable"));
    }
}
