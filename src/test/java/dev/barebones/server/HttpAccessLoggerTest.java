package dev.barebones.server;

import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpAccessLoggerTest {
    private HttpAccessLoggerTest() {
    }

    public static void main(String[] args) throws Exception {
        addsServerRequestIdsAndRecordsCompletedRequests();
        isolatesRequestsFromFailingLogSink();
        System.out.println("HTTP access logger tests passed");
    }

    private static void isolatesRequestsFromFailingLogSink() throws Exception {
        HttpRouter router = new HttpRouter()
                .register("GET", "/ok", exchange -> HttpResponses.send(exchange, 200, "ok\n"));
        HttpAccessLogger accessLogger = new HttpAccessLogger(router, event -> {
            throw new IllegalStateException("simulated sink failure");
        });
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", accessLogger);
        server.start();

        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + server.getAddress().getPort() + "/ok")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() == 200, "failing log sink changed response status");
            require(response.body().equals("ok\n"), "failing log sink changed response body");
        } finally {
            server.stop(0);
        }
    }

    private static void addsServerRequestIdsAndRecordsCompletedRequests() throws Exception {
        UUID firstId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        UUID secondId = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        ArrayDeque<UUID> requestIds = new ArrayDeque<>(List.of(firstId, secondId));
        AtomicLong nanoTime = new AtomicLong();
        List<ServerEvent> events = new ArrayList<>();

        HttpRouter router = new HttpRouter()
                .register("GET", "/ok", exchange -> HttpResponses.send(exchange, 200, "ok\n"));
        HttpAccessLogger accessLogger = new HttpAccessLogger(
                router,
                events::add,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                requestIds::remove,
                () -> nanoTime.getAndAdd(5_000));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", accessLogger);
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpResponse<String> ok = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/ok"))
                            .header(HttpAccessLogger.REQUEST_ID_HEADER, "client-controlled")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> missing = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/missing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            require(ok.headers().firstValue(HttpAccessLogger.REQUEST_ID_HEADER).orElse("").equals(firstId.toString()),
                    "server request ID did not replace client value");
            require(missing.headers().firstValue(HttpAccessLogger.REQUEST_ID_HEADER).orElse("")
                            .equals(secondId.toString()),
                    "second server request ID was unexpected");
            require(events.size() == 2, "unexpected access event count: " + events.size());
            require(events.get(0).fields().get("status").equals("200"), "success status was not recorded");
            require(events.get(0).fields().get("response_bytes").equals("3"),
                    "success response size was not recorded");
            require(events.get(0).fields().get("duration_us").equals("5"),
                    "request duration was not recorded");
            require(events.get(1).fields().get("status").equals("404"), "missing status was not recorded");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
