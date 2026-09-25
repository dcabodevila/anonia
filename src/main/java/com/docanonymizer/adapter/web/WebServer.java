package com.docanonymizer.adapter.web;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import java.util.Arrays;
import com.docanonymizer.domain.model.Finding;
import com.docanonymizer.domain.port.TextExtractorPort;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import com.docanonymizer.domain.service.DocumentNotProcessableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Interfaz de revision servida sobre el servidor HTTP del propio JDK.
 *
 * <p>Sin Spring y sin npm, no por minimalismo estetico sino porque el prototipo debe
 * construirse y arrancar sin acceso a la red. {@code com.sun.net.httpserver} viene en el
 * JDK y cubre de sobra lo que hace falta.
 *
 * <p>El flujo son dos llamadas, que es lo que exige una revision de verdad: primero se
 * analiza y se devuelven los CANDIDATOS, y solo despues, con las decisiones de la
 * persona, se sustituye y se pasa la puerta de salida. Una sola llamada obligaria a
 * decidir antes de haber visto nada.
 *
 * <p>Escucha en 127.0.0.1 salvo que se indique lo contrario. Dentro de un contenedor hay
 * que abrirlo a 0.0.0.0 para que el puerto publicado sea alcanzable, y ahi el aislamiento
 * lo aporta la propia red del contenedor.
 */
public final class WebServer {

    private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;
    private static final int THREADS = 4;

    private final AnonymizationPipeline pipeline = PipelineFactory.standard();
    private final JobStore jobs = new JobStore();
    private final String bindAddress;
    private final int port;

    public WebServer(String bindAddress, int port) {
        this.bindAddress = bindAddress;
        this.port = port;
    }

    public static void main(String[] args) throws IOException {
        String host = envOrDefault("DOC_ANONYMIZER_HOST", "127.0.0.1");
        int port = Integer.parseInt(envOrDefault("DOC_ANONYMIZER_PORT",
                envOrDefault("PORT", "8080")));
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        new WebServer(host, port).start();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.setExecutor(Executors.newFixedThreadPool(THREADS));

        server.createContext("/", this::serveStatic);
        server.createContext("/api/analyze", this::handleAnalyze);
        server.createContext("/api/apply", this::handleApply);
        server.createContext("/health", exchange ->
                respond(exchange, 200, "text/plain; charset=utf-8", "ok"));

        server.start();
        System.out.println("doc-anonymizer escuchando en http://" + bindAddress + ":" + port);
        System.out.println("Revision local: los valores en claro se muestran en la UI y "
                + "no salen del proceso.");
        return server;
    }

    // ---------------------------------------------------------------- analyze

    private void handleAnalyze(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondError(exchange, 405, "Metodo no permitido.");
            return;
        }
        Path temp = null;
        try {
            byte[] body = readLimited(exchange.getRequestBody());
            if (body.length == 0) {
                respondError(exchange, 400, "Cuerpo vacio: falta el documento.");
                return;
            }
            // Los extractores locales trabajan sobre fichero. El temporal vive lo minimo y se
            // borra siempre: es una copia del documento original en claro.
            temp = Files.createTempFile("doc-anonymizer-", uploadSuffix(body));
            Files.write(temp, body);

            long startedAt = System.nanoTime();
            AnonymizationPipeline.Analysis analysis = ExactOccurrences.expand(pipeline.analyze(temp));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            String jobId = jobs.put(analysis);
            respond(exchange, 200, "application/json; charset=utf-8",
                    analysisJson(jobId, analysis, elapsedMs));

        } catch (DocumentNotProcessableException | TextExtractorPort.ExtractionException e) {
            respondError(exchange, 422, e.getMessage());
        } catch (IOException e) {
            respondError(exchange, 500, "No se pudo leer el documento localmente.");
        } catch (IllegalStateException e) {
            respondError(exchange, 413, e.getMessage());
        } catch (RuntimeException e) {
            // Nunca se devuelve la traza: podria arrastrar fragmentos del documento.
            respondError(exchange, 500, "Error interno procesando el documento.");
        } finally {
            deleteQuietly(temp);
        }
    }

    private String analysisJson(
            String jobId, AnonymizationPipeline.Analysis analysis, long elapsedMs) {

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("jobId", Json.quote(jobId));
        fields.put("pageCount", Json.number(analysis.pageCount()));
        fields.put("elapsedMs", Json.number(elapsedMs));
        fields.put("sourceSha256", Json.quote(analysis.sourceSha256()));
        fields.put("text", Json.quote(analysis.normalizedText()));
        fields.put("detections", Json.array(analysis.candidates(), this::detectionJson));
        fields.put("types", Json.array(Arrays.asList(DetectionType.values()), type -> Json.quote(type.label())));
        return Json.object(fields);
    }

    /**
     * No se envia el valor: el cliente ya tiene el texto y los offsets, asi que puede
     * recortarlo. Evita mandar cada dato personal dos veces por el mismo canal.
     */
    private String detectionJson(Detection detection) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", Json.quote(detection.id()));
        fields.put("type", Json.quote(detection.type().label()));
        fields.put("start", Json.number(detection.start()));
        fields.put("end", Json.number(detection.end()));
        fields.put("entityKey", Json.quote(detection.entityKey()));
        fields.put("provenance", Json.quote(detection.provenance().name()));
        fields.put("confidence", Json.number(detection.confidence()));
        return Json.object(fields);
    }

    // ------------------------------------------------------------------ apply

    private void handleApply(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respondError(exchange, 405, "Metodo no permitido.");
            return;
        }
        try {
            String jobId = exchange.getRequestHeaders().getFirst("X-Job-Id");
            if (jobId == null || jobId.isBlank()) {
                respondError(exchange, 400, "Falta la cabecera X-Job-Id.");
                return;
            }
            AnonymizationPipeline.Analysis analysis = jobs.get(jobId).orElse(null);
            if (analysis == null) {
                respondError(exchange, 404,
                        "El analisis expiro o no existe. Vuelve a subir el documento.");
                return;
            }

            var reviewed = ReviewEdits.replay(analysis,
                    new String(readLimited(exchange.getRequestBody()), StandardCharsets.UTF_8));

            AnonymizationResult result = pipeline.complete(analysis, reviewed);
            respond(exchange, 200, "application/json; charset=utf-8", resultJson(result));

        } catch (IllegalArgumentException e) {
            respondError(exchange, 400, "Revision no valida. Vuelve a analizar el documento.");
        } catch (RuntimeException e) {
            respondError(exchange, 500, "Error interno generando el resultado.");
        }
    }

    private String resultJson(AnonymizationResult result) {
        List<String> labels = new ArrayList<>();
        result.pseudonyms().forEach((entityKey, label) -> labels.add(Json.object(Map.of(
                "entityKey", Json.quote(entityKey),
                "label", Json.quote(label)))));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("deliverable", Json.bool(result.deliverable()));
        fields.put("substitutions", Json.number(result.accepted().size()));
        fields.put("entities", Json.number(result.pseudonyms().size()));
        fields.put("markdown", Json.quote(result.markdown()));
        fields.put("labels", "[" + String.join(",", labels) + "]");
        fields.put("findings",
                Json.array(result.verification().findings(), this::findingJson));
        return Json.object(fields);
    }

    private String findingJson(Finding finding) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("severity", Json.quote(finding.severity().name()));
        fields.put("detail", Json.quote(finding.detail()));
        return Json.object(fields);
    }

    // ----------------------------------------------------------------- static

    private void serveStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String resource = switch (path) {
            case "/", "/index.html" -> "/web/index.html";
            case "/app.css" -> "/web/app.css";
            case "/app.js" -> "/web/app.js";
            case "/anonimuse.png" -> "/web/anonimuse.png";
            case "/anonimuse-logo.png" -> "/web/anonimuse-logo.png";
            case "/landing/detect.gif" -> "/web/landing/detect.gif";
            case "/landing/review.gif" -> "/web/landing/review.gif";
            case "/landing/compare.gif" -> "/web/landing/compare.gif";
            case "/landing/export.gif" -> "/web/landing/export.gif";
            default -> null;
        };
        if (resource == null) {
            respondError(exchange, 404, "No encontrado.");
            case "/landing/ai.gif" -> "/web/landing/ai.gif";
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                respondError(exchange, 500, "Recurso ausente en el empaquetado.");
                return;
            }
            respondBytes(exchange, 200, contentTypeOf(resource), in.readAllBytes());
        }
    }

    private String contentTypeOf(String resource) {
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".png")) {
            return "image/png";
        }
        if (resource.endsWith(".gif")) {
            return "image/gif";
        }
        return "text/html; charset=utf-8";
    }

    // ------------------------------------------------------------------ utils

    private String uploadSuffix(byte[] body) {
        if (body.length >= 5 && body[0] == '%' && body[1] == 'P' && body[2] == 'D'
                && body[3] == 'F' && body[4] == '-') {
            return ".pdf";
        }
        if (body.length >= 3 && (body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xd8
                && (body[2] & 0xff) == 0xff) {
            return ".jpg";
        }
        if (body.length >= 8 && (body[0] & 0xff) == 0x89 && body[1] == 'P' && body[2] == 'N'
                && body[3] == 'G' && body[4] == 0x0d && body[5] == 0x0a && body[6] == 0x1a
                && body[7] == 0x0a) {
            return ".png";
        }
        throw new TextExtractorPort.ExtractionException(
                "Solo se admiten documentos PDF y fotos JPEG o PNG.");
    }

    private byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes((int) MAX_UPLOAD_BYTES + 1);
        if (data.length > MAX_UPLOAD_BYTES) {
            throw new IllegalStateException(
                    "El fichero supera el limite de " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MB.");
        }
        return data;
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        respondBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private void respondBytes(
            HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // El contenido lleva datos personales: que no quede en ninguna cache.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void respondError(HttpExchange exchange, int status, String message)
            throws IOException {
        respond(exchange, status, "application/json; charset=utf-8",
                Json.object(Map.of("error", Json.quote(message))));
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // El temporal se borrara con el contenedor; no hay nada mejor que hacer aqui.
        }
    }
}
