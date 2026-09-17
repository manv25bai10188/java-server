package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HttpResponses {
    private HttpResponses() {
    }

    public static void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    public static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    public static void send(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var response = exchange.getResponseBody()) {
            response.write(body);
        }
    }
}
