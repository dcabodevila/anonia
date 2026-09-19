package com.docanonymizer.adapter.cli;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.Finding;
import com.docanonymizer.domain.port.TextExtractorPort;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import com.docanonymizer.domain.service.DocumentNotProcessableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Punto de entrada del prototipo.
 *
 * <p>Codigos de salida: 0 entregado, 1 error de uso o de proceso, 2 verificacion
 * bloqueada. Distinguir el 2 del 1 importa: significa que el pipeline funciono de
 * principio a fin y fue la puerta de salida la que decidio no entregar.
 */
public final class Main {

    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final int EXIT_BLOCKED = 2;

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printUsage();
            return EXIT_ERROR;
        }
        if (options.help()) {
            printUsage();
            return EXIT_OK;
        }

        try {
            return process(options);
        } catch (DocumentNotProcessableException | TextExtractorPort.ExtractionException e) {
            System.err.println("Documento rechazado: " + e.getMessage());
            return EXIT_ERROR;
        } catch (IOException e) {
            System.err.println("Error de entrada/salida: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static int process(Options options) throws IOException {
        AnonymizationPipeline pipeline = PipelineFactory.standard();

        long startedAt = System.nanoTime();
        AnonymizationResult result = pipeline.run(options.input());
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        printSummary(result, elapsedMs);

        if (options.showDetections()) {
            printDetections(result);
        }

        String report = TechnicalReport.render(result, Instant.now());
        if (!options.dryRun()) {
            Files.writeString(options.reportPath(), report, StandardCharsets.UTF_8);
            System.out.println("Informe tecnico: " + options.reportPath());
        }

        if (!result.deliverable()) {
            System.err.println();
            System.err.println("VERIFICACION BLOQUEADA. No se escribe el Markdown.");
            for (Finding finding : result.verification().blocking()) {
                System.err.println("  [" + finding.control() + "] " + finding.detail());
            }
            return EXIT_BLOCKED;
        }

        if (options.dryRun()) {
            System.out.println();
            System.out.println("Simulacion: verificacion superada, no se escribe nada.");
            return EXIT_OK;
        }

        Files.writeString(options.outputPath(), result.markdown(), StandardCharsets.UTF_8);
        System.out.println("Markdown desidentificado: " + options.outputPath());
        return EXIT_OK;
    }

    private static void printSummary(AnonymizationResult result, long elapsedMs) {
        System.out.println("Paginas procesadas .... " + result.pageCount());
        System.out.println("Entidades distintas ... " + result.pseudonyms().size());
        System.out.println("Sustituciones ......... " + result.accepted().size());
        System.out.println("Verificacion .......... "
                + (result.deliverable() ? "SUPERADA" : "BLOQUEADA"));
        System.out.println("Tiempo ................ " + elapsedMs + " ms");
    }

    /**
     * Sustituto de la interfaz de revision. Imprime los valores en claro, que es
     * justamente lo que un revisor necesita ver y lo que jamas debe acabar en un fichero.
     */
    private static void printDetections(AnonymizationResult result) {
        System.out.println();
        System.out.println("--- DETECCIONES (CONTIENE DATOS PERSONALES) ---");
        for (Detection detection : result.accepted()) {
            System.out.printf("  %-10s %-16s conf=%.2f  %s -> %s%n",
                    detection.type().label(),
                    detection.provenance(),
                    detection.confidence(),
                    detection.value().replace("\n", " "),
                    result.pseudonyms().get(detection.entityKey()));
        }
        System.out.println("--- FIN DETECCIONES ---");
    }

    private static void printUsage() {
        System.out.println("""
                doc-anonymizer - PDF nativo -> Markdown desidentificado (100% offline)

                Uso:
                  doc-anonymizer <entrada.pdf> [opciones]

                Opciones:
                  -o, --output <fichero>   Markdown de salida (por defecto: <entrada>.anon.md)
                      --report <fichero>   Informe tecnico    (por defecto: <entrada>.report.md)
                      --show-detections    Muestra los valores detectados por consola.
                                           CONTIENE DATOS PERSONALES: solo para revision.
                      --dry-run            Procesa y verifica, pero no escribe ficheros.
                  -h, --help               Esta ayuda.

                Codigos de salida:
                  0  documento entregado
                  1  error de uso o documento rechazado
                  2  verificacion bloqueada: el resultado NO se escribe
                """);
    }

    record Options(
            Path input,
            Path outputPath,
            Path reportPath,
            boolean showDetections,
            boolean dryRun,
            boolean help) {

        static Options parse(String[] args) {
            if (args.length == 0) {
                return new Options(null, null, null, false, false, true);
            }
            Path input = null;
            Path output = null;
            Path report = null;
            boolean showDetections = false;
            boolean dryRun = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> {
                        return new Options(null, null, null, false, false, true);
                    }
                    case "-o", "--output" -> output = Path.of(requireValue(args, ++i, arg));
                    case "--report" -> report = Path.of(requireValue(args, ++i, arg));
                    case "--show-detections" -> showDetections = true;
                    case "--dry-run" -> dryRun = true;
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Opcion desconocida: " + arg);
                        }
                        if (input != null) {
                            throw new IllegalArgumentException(
                                    "Solo se admite un fichero de entrada.");
                        }
                        input = Path.of(arg);
                    }
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("Falta el fichero PDF de entrada.");
            }
            return new Options(
                    input,
                    output != null ? output : siblingWithSuffix(input, ".anon.md"),
                    report != null ? report : siblingWithSuffix(input, ".report.md"),
                    showDetections,
                    dryRun,
                    false);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " necesita un valor.");
            }
            return args[index];
        }

        private static Path siblingWithSuffix(Path input, String suffix) {
            String fileName = input.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            Path parent = input.toAbsolutePath().getParent();
            return parent.resolve(base + suffix);
        }
    }
}
