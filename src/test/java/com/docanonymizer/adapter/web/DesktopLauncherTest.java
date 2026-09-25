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

    @Test void installerSeedsOnlyCommentedRulesAndPreservesExistingUserFile() throws Exception {
        Path resources = Path.of("packaging/windows/jpackage-resources");
        String wix = Files.readString(resources.resolve("main.wxs"));
        String sample = Files.readString(resources.resolve("rules-example.txt"));
        assertTrue(wix.contains("Value=\"[%USERPROFILE]\\.anonimuse\""));
        assertTrue(wix.contains("Before=\"CostFinalize\""));
        assertTrue(wix.contains("<FileSearch") && wix.contains("Name=\"rules.txt\""));
        assertTrue(wix.contains("NOT EXISTING_USER_RULES"));
        assertTrue(wix.contains("Permanent=\"yes\""));
        assertTrue(wix.contains("<?include \"STAGED_RULES_OVERRIDE\" ?>"),
                "WiX include must quote the XML-escaped staged path, which may contain spaces");
        assertTrue(wix.contains("Source=\"$(var.JpRulesSource)\""));
        assertTrue(wix.contains("NeverOverwrite=\"yes\""));
        assertEquals(6, sample.lines().filter(line -> line.matches("^# (person|organization|term|exclude-person|exclude-organization|exclude-term): .+")).count());
        for (String kind : List.of("person", "organization", "term")) {
            assertTrue(sample.contains("# " + kind + ": "));
            assertTrue(sample.contains("# exclude-" + kind + ": "));
        }
        assertTrue(sample.lines().allMatch(line -> line.isBlank() || line.stripLeading().startsWith("#")));
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @Test void msiPlanUsesSameProductIdentityAndRenamesFreshOutputWithoutOverwriting() throws Exception {
        Path script = Path.of("packaging/windows/build-installer.ps1");
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Plan", "-Msi")
                .redirectErrorStream(true).start();
        String plan = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), plan);
        for (String expected : List.of("--type", "msi", "--name", "anonimuse",
                "--win-per-user-install", "--win-upgrade-uuid",
                "ce0936d4-f914-4f47-9052-5b286df59f57", "--resource-dir", "windows-resources-",
                "com.docanonymizer.adapter.web.DesktopLauncher")) {
            assertTrue(plan.contains(expected), expected + " missing: " + plan);
        }
        assertFalse(plan.contains("\"exe\""), "MSI plan must not select EXE: " + plan);
        String source = Files.readString(script);
        assertTrue(source.contains("if ($Msi) { 'msi' } else { 'exe' }"));
        assertTrue(source.contains("anonimuse-0.4.0.$extension"));
        assertTrue(source.contains("anonimuse-installer.$extension"));
        assertTrue(source.contains("Move-Item -LiteralPath $generatedInstaller -Destination $installer -ErrorAction Stop"));
        assertTrue(source.contains("Test-Path -LiteralPath $generatedInstaller"));
        assertTrue(source.contains("Test-Path -LiteralPath $installer"));
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
        assertTrue(plan.contains("0.4.0"), "installer version missing: " + plan);
        assertTrue(plan.contains("anonimuse"), "application name missing: " + plan);
        assertTrue(plan.contains("--icon"), "Windows icon option missing: " + plan);
        assertTrue(plan.contains("anonimuse-logo.ico"), "Windows .ico icon missing: " + plan);
        for (String expected : List.of("--type", "exe", "--win-per-user-install", "--win-menu",
                "--win-shortcut", "--win-console", "--main-class",
                "com.docanonymizer.adapter.web.DesktopLauncher", "doc-anonymizer.jar",
                "windows-installer", "windows-input-", "--add-modules", "ALL-MODULE-PATH")) {
            assertTrue(plan.contains(expected), expected + " missing: " + plan);
        }
        assertFalse(plan.contains("--runtime-image"), "jpackage must create bundled runtime");
        assertTrue(plan.contains("--resource-dir"), "jpackage resource directory missing: " + plan);
        assertTrue(plan.contains("windows-resources-"), "isolated jpackage resource directory missing: " + plan);
        String scriptSource = Files.readString(script);
        assertTrue(scriptSource.contains("<Include><?define JpRulesSource=`\"$escapedSample`\"?></Include>"),
                "generated WiX include must wrap the source definition in an Include root");
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
        assertTrue(source.contains("src/main/resources/web/anonimuse-logo.png"),
                "Windows icon must derive from the application logo PNG");
        assertTrue(source.contains("mvn -o package"));
        assertTrue(source.contains("anonimuse-installer.$extension"),
                "packaging must publish the exact installer filename for the selected format");
        assertTrue(source.contains("Move-Item -LiteralPath $generatedInstaller -Destination $installer -ErrorAction Stop"),
                "packaging must rename the freshly generated installer");
        assertTrue(source.contains("anonimuse-0.4.0.$extension"),
                "packaging must identify the jpackage versioned output");
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
