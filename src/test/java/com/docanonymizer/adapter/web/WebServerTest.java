package com.docanonymizer.adapter.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @Test void servesOnlyApprovedBrandImagesWithTheirOriginalBytes() throws Exception {
        int port = freePort();
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
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
