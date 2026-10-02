package com.docanonymizer.adapter.web;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DesktopLauncherTest {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    private static final class FakeServer extends HttpServer {
        private final int port;
        boolean stopped;
        java.util.concurrent.Executor executor;
        FakeServer(int port) { this.port = port; }
        public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", port); }
        public void stop(int delay) { stopped = true; }
        public void start() {}
        public void bind(InetSocketAddress address, int backlog) {}
        public void setExecutor(java.util.concurrent.Executor executor) { this.executor = executor; }
        public java.util.concurrent.Executor getExecutor() { return executor; }
        public com.sun.net.httpserver.HttpContext createContext(String path) { return null; }
        public com.sun.net.httpserver.HttpContext createContext(String path, com.sun.net.httpserver.HttpHandler handler) { return null; }
        public void removeContext(String path) {}
        public void removeContext(com.sun.net.httpserver.HttpContext context) {}
    }

    private static final class WindowFixture implements EdgeAppWindow.Profiles {
        final List<List<String>> commands = new ArrayList<>();
        final List<Path> created = new ArrayList<>();
        final List<Path> deleted = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        long duration = 3_000_000_000L;
        boolean failStart;
        boolean edgePresent = true;
        Runnable onDelete = () -> {};
        final Process process = new Process() {
            public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            public int waitFor() { clock.addAndGet(duration); return 0; }
            public int exitValue() { return 0; }
            public void destroy() { fail("must not destroy window process"); }
        };
        public Path create() {
            Path path = Path.of("fake-profile-" + created.size());
            created.add(path);
            return path;
        }
        public void delete(Path profile) {
            onDelete.run();
            deleted.add(profile);
        }
        EdgeAppWindow window() {
            return new EdgeAppWindow("Windows 11", name -> "edge-root", path -> edgePresent,
                    command -> {
                        commands.add(List.copyOf(command));
                        if (failStart) throw new java.io.IOException("cannot start");
                        return process;
                    }, this, clock::get);
        }
    }

    @Test void discoversEdgeInWindowsOrderAndSkipsMissingRoots() {
        List<Path> probes = new ArrayList<>();
        java.util.Map<String, String> roots = java.util.Map.of(
                "ProgramFiles(x86)", "x86", "ProgramFiles", "programs", "LOCALAPPDATA", "local");
        for (String chosen : List.of("x86", "programs", "local")) {
            probes.clear();
            Path expected = Path.of(chosen, "Microsoft", "Edge", "Application", "msedge.exe");
            EdgeAppWindow window = new EdgeAppWindow("Windows 11", roots::get, path -> {
                probes.add(path);
                return path.equals(expected);
            }, command -> fail("no process expected"), new WindowFixture(), () -> 0);
            assertEquals(expected, window.discover());
            assertEquals(List.of("x86", "programs", "local").subList(0, probes.size()),
                    probes.stream().map(path -> path.getName(0).toString()).toList());
        }
        EdgeAppWindow nonWindows = new EdgeAppWindow("Darwin", name -> fail("environment read"),
                path -> fail("file probed"), command -> fail("process started"), new WindowFixture(), () -> 0);
        assertNull(nonWindows.discover());
        EdgeAppWindow localOnly = new EdgeAppWindow("Windows", name -> name.equals("LOCALAPPDATA") ? "local" : null,
                path -> true, command -> fail("process started"), new WindowFixture(), () -> 0);
        assertEquals(Path.of("local", "Microsoft", "Edge", "Application", "msedge.exe"), localOnly.discover());
    }

    @Test void edgeUsesBoundPortExactArgumentsAndFreshProfileAndStopsServer() {
        WindowFixture fixture = new WindowFixture();
        for (int i = 0; i < 2; i++) {
            FakeServer server = new FakeServer(43210);
            List<Integer> attempts = new ArrayList<>();
            assertEquals(0, DesktopLauncher.launch(new String[0], (host, port) -> {
                attempts.add(port);
                if (port == 18080) throw new java.net.BindException("occupied");
                return server;
            }, uri -> fail("browser opened"), out, fixture.window()));
            assertEquals(List.of(18080, 0), attempts);
            assertTrue(server.stopped);
            assertEquals(List.of(Path.of("edge-root", "Microsoft", "Edge", "Application", "msedge.exe").toString(),
                    "--app=http://127.0.0.1:43210/", "--user-data-dir=" + fixture.created.get(i),
                    "--no-first-run", "--no-default-browser-check"), fixture.commands.get(i));
        }
        assertNotEquals(fixture.created.get(0), fixture.created.get(1));
        assertEquals(fixture.created, fixture.deleted);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("ventana de escritorio"));
    }

    @Test void missingEdgeFallsBackWithoutProfileOrStoppingServer() {
        WindowFixture fixture = new WindowFixture();
        fixture.edgePresent = false;
        assertFallback(fixture);
        assertTrue(fixture.created.isEmpty());
        assertTrue(fixture.commands.isEmpty());
    }

    @Test void processStartFailureFallsBackAndDeletesProfile() {
        WindowFixture fixture = new WindowFixture();
        fixture.failStart = true;
        assertFallback(fixture);
        assertEquals(fixture.created, fixture.deleted);
        assertEquals(1, fixture.deleted.size());
    }

    @Test void quickProcessExitFallsBackWithoutStoppingServerAndDeletesProfile() {
        WindowFixture fixture = new WindowFixture();
        fixture.duration = 1_999_999_999L;
        assertFallback(fixture);
        assertEquals(fixture.created, fixture.deleted);
        assertEquals(1, fixture.deleted.size());
    }

    @Test void closingWindowShutsDownExecutorBeforeDeletingProfile() {
        WindowFixture fixture = new WindowFixture();
        FakeServer server = new FakeServer(9090);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        fixture.onDelete = () -> {
            assertTrue(server.stopped);
            assertTrue(executor.isShutdown());
        };
        try {
            assertEquals(0, DesktopLauncher.launch(new String[]{"9090"}, (host, port) -> server,
                    uri -> fail("browser opened"), out, fixture.window()));
            assertEquals(1, fixture.deleted.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void recursiveProfileCleanupIsIdempotent(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path profile = Files.createDirectory(temp.resolve("profile"));
        Path nested = Files.createDirectories(profile.resolve("Default/cache/nested"));
        Files.writeString(nested.resolve("data"), "cache");
        EdgeAppWindow.deleteProfile(profile);
        assertFalse(Files.exists(profile));
        assertDoesNotThrow(() -> EdgeAppWindow.deleteProfile(profile));
        assertTrue(Files.exists(temp));
    }

    @Test void twoSecondWindowLifetimeStopsServer() {
        WindowFixture fixture = new WindowFixture();
        fixture.duration = 2_000_000_000L;
        FakeServer server = new FakeServer(9090);
        assertEquals(0, DesktopLauncher.launch(new String[]{"9090"}, (host, port) -> server,
                uri -> fail("browser opened"), out, fixture.window()));
        assertTrue(server.stopped);
    }

    private void assertFallback(WindowFixture fixture) {
        FakeServer server = new FakeServer(9090);
        List<java.net.URI> opened = new ArrayList<>();
        assertEquals(0, DesktopLauncher.launch(new String[]{"9090"}, (host, port) -> server,
                opened::add, out, fixture.window()));
        assertFalse(server.stopped);
        assertEquals(List.of(java.net.URI.create("http://127.0.0.1:9090/")), opened);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Ctrl+C"));
    }

    @Test void desktopDefaultRequests18080() {
        List<Integer> ports = new ArrayList<>();
        DesktopLauncher.launch(new String[0], (host, port) -> { ports.add(port); return new FakeServer(port); }, uri -> {}, out);
        assertEquals(List.of(18080), ports);
    }

    @Test void startsLoopbackBeforeOpeningBrowser() {
        List<String> events = new ArrayList<>();
        assertEquals(0, DesktopLauncher.launch(new String[0],
                (host, port) -> { events.add(host + ":" + port); return new FakeServer(port); },
                uri -> events.add(uri.toString()), out));
        assertEquals(List.of("127.0.0.1:18080", "http://127.0.0.1:18080/"), events);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Ctrl+C"));
    }

    @Test void occupiedDefaultRetriesOnActualBoundSocketAndOpensRealUrl() throws Exception {
        List<Integer> attempts = new ArrayList<>();
        List<HttpServer> servers = new ArrayList<>();
        List<java.net.URI> opened = new ArrayList<>();
        try (ServerSocket collision = new ServerSocket()) {
            collision.bind(new InetSocketAddress("127.0.0.1", 0));
            int occupied = collision.getLocalPort();
            assertEquals(0, DesktopLauncher.launch(new String[0], (host, port) -> {
                attempts.add(port);
                if (port == 18080) {
                    HttpServer.create(new InetSocketAddress(host, occupied), 0);
                }
                HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
                servers.add(server);
                server.start();
                return server;
            }, opened::add, out));
            assertEquals(List.of(18080, 0), attempts);
            assertEquals(1, opened.size());
            assertEquals(servers.get(0).getAddress().getPort(), opened.get(0).getPort());
        } finally {
            servers.forEach(server -> server.stop(0));
        }
    }

    @Test void explicitOccupiedPortDoesNotRetry() {
        List<Integer> attempts = new ArrayList<>();
        assertEquals(1, DesktopLauncher.launch(new String[]{"9090"}, (host, port) -> {
            attempts.add(port);
            throw new java.net.BindException("occupied");
        }, uri -> fail("browser opened"), out));
        assertEquals(List.of(9090), attempts);
    }

    @Test void nonBindFailureDoesNotRetry() {
        List<Integer> attempts = new ArrayList<>();
        assertEquals(1, DesktopLauncher.launch(new String[0], (host, port) -> {
            attempts.add(port);
            throw new IllegalStateException("startup failed");
        }, uri -> fail("browser opened"), out));
        assertEquals(List.of(18080), attempts);
    }

    @Test void startupFailureNeverOpensBrowser() {
        List<String> opened = new ArrayList<>();
        assertEquals(1, DesktopLauncher.launch(new String[0],
                (host, port) -> { throw new java.net.BindException("busy"); },
                uri -> opened.add(uri.toString()), out));
        assertTrue(opened.isEmpty());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("18080"));
    }

    @Test void browserFailureLeavesServerAvailableAndPrintsUrl() {
        assertEquals(0, DesktopLauncher.launch(new String[]{"9090"}, (host, port) -> {
            assertEquals("127.0.0.1", host);
            assertEquals(9090, port);
            return new FakeServer(port);
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
        assertTrue(source.contains("anonimuse-0.4.3.$extension"));
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
        assertTrue(plan.contains("0.4.3"), "installer version missing: " + plan);
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
        assertTrue(source.contains("anonimuse-0.4.3.$extension"),
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
