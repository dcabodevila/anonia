package com.docanonymizer.adapter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.format.DocumentFormat;
import com.docanonymizer.adapter.format.DocumentFormatRegistry;
import com.docanonymizer.adapter.format.TextFormat;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Registering a format is enough for the browser picker to offer it and the server to analyse it. */
class ServedPickerTest {

    @Test void servedPickerAcceptsExactlyTheStandardRegistry() throws Exception {
        DocumentFormatRegistry standard = DocumentFormatRegistry.standard();

        for (String path : List.of("/", "/index.html")) {
            assertEquals(standard.acceptAttribute(), servedAccept(standard, path));
        }
    }

    @Test void servedPickerOffersANewlyRegisteredFormat() throws Exception {
        List<DocumentFormat> formats = new ArrayList<>(DocumentFormatRegistry.standard().formats());
        formats.add(new TextFormat("Futuro", List.of(".future"), List.of("text/x-future"),
                source -> new ExtractedDocument(List.of(new PageText(1, List.of("futuro"))), "sha")));
        DocumentFormatRegistry registry = new DocumentFormatRegistry(formats);

        String accept = servedAccept(registry, "/");

        assertEquals(registry.acceptAttribute(), accept);
        assertTrue(accept.contains(".future") && accept.contains("text/x-future"), accept);
    }

    @Test void aNewlyRegisteredFormatIsAlsoAnalysed() throws Exception {
        List<DocumentFormat> formats = new ArrayList<>(DocumentFormatRegistry.standard().formats());
        formats.add(new TextFormat("Futuro", List.of(".future"), List.of("text/x-future"),
                source -> ExtractedDocument.nativeText(List.of(new PageText(1,
                        List.of("Juan García Pérez firmó con DNI 12345678Z."))), "sha")));
        HttpServer server = new WebServer("127.0.0.1", 0, new DocumentFormatRegistry(formats)).start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/analyze"))
                            .header("X-File-Name", "nota.future")
                            .POST(HttpRequest.BodyPublishers.ofString("contenido futuro", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("12345678Z"), response.body());
        } finally {
            server.stop(0);
        }
    }

    private static String servedAccept(DocumentFormatRegistry registry, String path) throws Exception {
        HttpServer server = new WebServer("127.0.0.1", 0, registry).start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            Matcher accept = Pattern.compile("<input type=\"file\" id=\"file\" accept=\"([^\"]*)\"")
                    .matcher(response.body());
            assertTrue(accept.find(), "file input not found");
            return accept.group(1);
        } finally {
            server.stop(0);
        }
    }
}
