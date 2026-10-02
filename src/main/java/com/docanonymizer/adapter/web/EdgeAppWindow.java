package com.docanonymizer.adapter.web;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Colaboradores del modo ventana; no depende del navegador predeterminado. */
final class EdgeAppWindow {
    private static final long MIN_WINDOW_NANOS = 2_000_000_000L;

    @FunctionalInterface
    interface ProcessStarter { Process start(List<String> command) throws Exception; }
    interface Profiles {
        Path create() throws Exception;
        void delete(Path profile);
    }

    private final String os;
    private final Function<String, String> environment;
    private final Predicate<Path> file;
    private final ProcessStarter processes;
    private final Profiles profiles;
    private final LongSupplier clock;

    EdgeAppWindow(String os, Function<String, String> environment, Predicate<Path> file,
                  ProcessStarter processes, Profiles profiles, LongSupplier clock) {
        this.os = os;
        this.environment = environment;
        this.file = file;
        this.processes = processes;
        this.profiles = profiles;
        this.clock = clock;
    }

    static EdgeAppWindow system() {
        return new EdgeAppWindow(System.getProperty("os.name"), System::getenv, Files::isRegularFile,
                command -> new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start(), new Profiles() {
                    public Path create() throws IOException {
                        // Un perfil compartido entregaria la ventana a otro proceso de Edge.
                        return Files.createTempDirectory("anonimuse-edge-");
                    }
                    public void delete(Path profile) { deleteProfile(profile); }
                }, System::nanoTime);
    }

    Path discover() {
        if (!os.startsWith("Windows")) return null;
        for (String name : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String root = environment.apply(name);
            if (root == null || root.isBlank()) continue;
            Path candidate = Path.of(root, "Microsoft", "Edge", "Application", "msedge.exe");
            if (file.test(candidate)) return candidate;
        }
        return null;
    }

    boolean openAndWait(URI uri, Runnable stop, PrintStream out) {
        Path profile = null;
        boolean completed = false;
        try {
            Path executable = discover();
            if (executable == null) return false;
            profile = profiles.create();
            Process process = processes.start(List.of(executable.toString(), "--app=" + uri,
                    "--user-data-dir=" + profile, "--no-first-run", "--no-default-browser-check"));
            long started = clock.getAsLong();
            out.println("Abriendo anonimuse en una ventana de escritorio...");
            process.waitFor();
            // Una salida antes de dos segundos puede ser un traspaso a otra instancia
            // o un fallo de Edge: mantenemos el servidor y abrimos el navegador habitual.
            if (clock.getAsLong() - started < MIN_WINDOW_NANOS) return false;
            completed = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (completed) stop.run();
            } finally {
                if (profile != null) {
                    try {
                        profiles.delete(profile);
                    } catch (RuntimeException ignored) {
                        // La limpieza es de mejor esfuerzo y no impide el cierre ni el respaldo.
                    }
                }
            }
        }
        return true;
    }

    static void deleteProfile(Path profile) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (!Files.exists(profile, LinkOption.NOFOLLOW_LINKS)) return;
                // No seguimos enlaces: solo se elimina el perfil creado para esta ventana.
                Files.walkFileTree(profile, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                return;
            } catch (IOException | RuntimeException ignored) {
                // Edge puede retener ficheros brevemente despues de cerrar la ventana.
            }
            if (attempt < 4) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
