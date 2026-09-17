package dev.barebones.server;

import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class HttpRouterTest {
    private HttpRouterTest() {
    }

    public static void main(String[] args) throws Exception {
        routesExactMethodAndPath();
        rejectsDuplicateRoutes();
        System.out.println("HTTP router tests passed");
    }

    private static void routesExactMethodAndPath() throws Exception {
        HttpRouter router = new HttpRouter()
                .register("GET", "/hello", exchange -> HttpResponses.send(exchange, 200, "hello\n"))
                .register("POST", "/hello", exchange -> HttpResponses.send(exchange, 201, "created\n"));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", router);
        server.start();

        try {
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

            HttpResponse<String> found = send(client, baseUrl + "/hello?name=test", "GET");
            require(found.statusCode() == 200, "registered route status was " + found.statusCode());
            require(found.body().equals("hello\n"), "registered route body was unexpected");

            HttpResponse<String> wrongMethod = send(client, baseUrl + "/hello", "PUT");
            require(wrongMethod.statusCode() == 405, "wrong method status was " + wrongMethod.statusCode());
            require(wrongMethod.headers().firstValue("Allow").orElse("").equals("GET, POST"),
                    "Allow header was unexpected");

            require(send(client, baseUrl + "/missing", "GET").statusCode() == 404,
                    "unknown route did not return 404");
            require(send(client, baseUrl + "/hello/child", "GET").statusCode() == 404,
                    "route matching was not exact");
        } finally {
            server.stop(0);
        }
    }

    private static void rejectsDuplicateRoutes() {
        HttpRouter router = new HttpRouter()
                .register("get", "/duplicate", exchange -> HttpResponses.send(exchange, 200, "first"));
        try {
            router.register("GET", "/duplicate", exchange -> HttpResponses.send(exchange, 200, "second"));
            throw new AssertionError("duplicate route was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected duplicate registration failure.
        }
    }

    private static HttpResponse<String> send(HttpClient client, String uri, String method) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(uri))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
