package dev.barebones.server;

import com.sun.net.httpserver.HttpServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class AuthenticationHandlerTest {
    private static final long NOW = 1_800_000_000L;
    private static final byte[] SECRET = "handler-test-secret-with-32-characters".getBytes(StandardCharsets.UTF_8);

    private AuthenticationHandlerTest() {
    }

    public static void main(String[] args) throws Exception {
        authenticatesBufferedRequestBodiesAndRejectsReplay();
        System.out.println("Authentication handler tests passed");
    }

    private static void authenticatesBufferedRequestBodiesAndRejectsReplay() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        HmacAuthenticator authenticator = new HmacAuthenticator(
                SECRET, new ReplayProtector(30, 100, clock), clock);
        HttpRouter router = new HttpRouter().register("POST", "/echo", exchange -> {
            byte[] body = HttpExchangeTelemetry.requestBody(exchange);
            HttpResponses.send(exchange, 200, body, "application/octet-stream");
        });
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", new AuthenticationHandler(router, authenticator));
        server.start();

        try {
            byte[] body = "authenticated body".getBytes(StandardCharsets.UTF_8);
            String nonce = "handler-nonce-000001";
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/echo?mode=test");
            String signature = authenticator.signHttp("POST", "/echo?mode=test", NOW, nonce, body);
            HttpRequest signedRequest = HttpRequest.newBuilder(uri)
                    .header(HmacAuthenticator.TIMESTAMP_HEADER, Long.toString(NOW))
                    .header(HmacAuthenticator.NONCE_HEADER, nonce)
                    .header(HmacAuthenticator.AUTHORIZATION_HEADER, signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> accepted = client.send(signedRequest, HttpResponse.BodyHandlers.ofByteArray());
            require(accepted.statusCode() == 200, "signed request status was " + accepted.statusCode());
            require(java.util.Arrays.equals(accepted.body(), body), "buffered request body changed");

            HttpResponse<String> replayed = client.send(signedRequest, HttpResponse.BodyHandlers.ofString());
            require(replayed.statusCode() == 401, "replay status was " + replayed.statusCode());
            require(replayed.headers().firstValue("WWW-Authenticate").orElse("")
                            .equals(HmacAuthenticator.SCHEME),
                    "authentication challenge was missing");

            HttpResponse<String> unsigned = client.send(
                    HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            require(unsigned.statusCode() == 401, "unsigned request status was " + unsigned.statusCode());
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
