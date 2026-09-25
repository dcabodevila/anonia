package com.docanonymizer.adapter.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {

    @Test void findingsApiOmitsInternalControlCodesAndSensitiveValues() throws Exception {
        var method = WebServer.class.getDeclaredMethod("findingJson", com.docanonymizer.domain.model.Finding.class);
        method.setAccessible(true);
        var finding = com.docanonymizer.domain.model.Finding.blocking(
                "C1-VALOR-LITERAL", "DNI en línea 2 sigue presente literalmente en la salida");
        String json = (String) method.invoke(new WebServer("127.0.0.1", 0), finding);
        assertTrue(json.contains("DNI en línea 2"));
        assertTrue(json.contains("BLOCKING"));
        assertTrue(!json.contains("C1-VALOR-LITERAL"));
        assertTrue(!json.contains("control"));
        assertTrue(!json.contains("12345678Z"));
    }

    @Test void usesDocumentPortBeforeRenderPort() throws Exception {
        int documentPort = availablePort();
        int renderPort = availablePort();

        assertStartsOn(documentPort, Map.of(
                "DOC_ANONYMIZER_PORT", String.valueOf(documentPort),
                "PORT", String.valueOf(renderPort)), new String[0]);
    }

    @Test void usesRenderPortWhenDocumentPortIsAbsent() throws Exception {
        int renderPort = availablePort();

        assertStartsOn(renderPort, Map.of("PORT", String.valueOf(renderPort)), new String[0]);
    }

    @Test void usesDefaultPortWhenNoPortEnvironmentVariableIsSet() throws Exception {
        assertStartsOn(8080, Map.of(), new String[0]);
    }

    @Test void commandLinePortStillWinsOverEnvironmentVariables() throws Exception {
        int commandLinePort = availablePort();

        assertStartsOn(commandLinePort, Map.of(
                "DOC_ANONYMIZER_PORT", String.valueOf(availablePort()),
                "PORT", String.valueOf(availablePort())),
                new String[]{String.valueOf(commandLinePort)});
    }

    @Test void servesOnlyApprovedBrandImagesWithTheirOriginalBytes() throws Exception {
        int port = availablePort();
        HttpServer server = new WebServer("127.0.0.1", port).start();
        try {
            HttpClient client = HttpClient.newHttpClient();

            for (String asset : new String[]{"anonimuse.png", "anonimuse-logo.png"}) {
                HttpResponse<byte[]> response = get(client, port, "/" + asset);
                assertEquals(200, response.statusCode(), asset);
                assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
                assertTrue(Arrays.equals(resourceBytes("/web/" + asset), response.body()), asset);
            }

            assertEquals(404, get(client, port, "/unapproved.png").statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test void servesLandingGifsWithTheirOriginalBytesAndRejectsUnknownNames() throws Exception {
        int port = availablePort();
        HttpServer server = new WebServer("127.0.0.1", port).start();
        try {
            HttpClient client = HttpClient.newHttpClient();

            for (String name : new String[]{"detect", "review", "compare", "export", "ai"}) {
                HttpResponse<byte[]> response = get(client, port, "/landing/" + name + ".gif");
                assertEquals(200, response.statusCode(), name);
                assertEquals("image/gif", response.headers().firstValue("Content-Type").orElseThrow());
                assertTrue(Arrays.equals(resourceBytes("/web/landing/" + name + ".gif"), response.body()), name);
            }

            assertEquals(404, get(client, port, "/landing/unknown.gif").statusCode());
        } finally {
            server.stop(0);
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private void assertStartsOn(int port, Map<String, String> environment, String[] arguments)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(javaCommand(), "-cp", System.getProperty("java.class.path"),
                WebServer.class.getName());
        builder.command().addAll(java.util.List.of(arguments));
        builder.redirectErrorStream(true);
        builder.environment().remove("DOC_ANONYMIZER_PORT");
        builder.environment().remove("PORT");
        builder.environment().put("DOC_ANONYMIZER_HOST", "127.0.0.2");
        builder.environment().putAll(environment);

        Process process = builder.start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String startup = output.readLine();
            assertTrue(startup.contains(":" + String.valueOf(port)), startup);
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static HttpResponse<byte[]> get(HttpClient client, int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] resourceBytes(String resource) throws IOException {
        try (var stream = WebServerTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return stream.readAllBytes();
        }
    }
}
