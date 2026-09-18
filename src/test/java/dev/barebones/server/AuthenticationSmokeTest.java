package dev.barebones.server;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public final class AuthenticationSmokeTest {
    private static final char[] SECRET = "integration-secret-with-at-least-32-characters".toCharArray();

    private AuthenticationSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 18_446, 19_996, Path.of("certs/server.p12"), "changeit".toCharArray(),
                32, 32, 100, 100, 1_000, SECRET, 30, 1_000);
        HmacAuthenticator clientAuthenticator = new HmacAuthenticator(config);

        try (DualProtocolServer server = new DualProtocolServer(config);
             DatagramSocket socket = new DatagramSocket()) {
            server.start();
            verifyHttps(config.httpsPort(), clientAuthenticator);
            verifyUdp(socket, config.udpPort(), clientAuthenticator);
        }

        System.out.println("Authenticated HTTPS and UDP smoke tests passed");
    }

    private static void verifyHttps(int port, HmacAuthenticator authenticator) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustTestCertificate()).build();
        URI uri = URI.create("https://localhost:" + port + "/echo");
        byte[] body = "signed over https".getBytes(StandardCharsets.UTF_8);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = "smoke-http-nonce-0001";
        HttpRequest signed = HttpRequest.newBuilder(uri)
                .header(HmacAuthenticator.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(HmacAuthenticator.NONCE_HEADER, nonce)
                .header(HmacAuthenticator.AUTHORIZATION_HEADER,
                        authenticator.signHttp("POST", "/echo", timestamp, nonce, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> accepted = client.send(signed, HttpResponse.BodyHandlers.ofByteArray());
        require(accepted.statusCode() == 200, "authenticated HTTPS status was " + accepted.statusCode());
        require(Arrays.equals(accepted.body(), body), "authenticated HTTPS response changed");

        HttpResponse<String> replayed = client.send(signed, HttpResponse.BodyHandlers.ofString());
        require(replayed.statusCode() == 401, "replayed HTTPS status was " + replayed.statusCode());

        HttpResponse<String> unsigned = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        require(unsigned.statusCode() == 401, "unsigned HTTPS status was " + unsigned.statusCode());
    }

    private static void verifyUdp(DatagramSocket socket, int port, HmacAuthenticator authenticator)
            throws Exception {
        socket.setSoTimeout(3_000);
        byte[] unsigned = "PING".getBytes(StandardCharsets.UTF_8);
        require(new String(sendUdp(socket, port, unsigned), StandardCharsets.UTF_8).equals("ERROR: unauthorized"),
                "unsigned UDP packet was accepted");

        byte[] signed = authenticator.wrapUdp(
                unsigned, Instant.now().getEpochSecond(),
                UUID.fromString("123e4567-e89b-12d3-a456-426614174001"));
        require(new String(sendUdp(socket, port, signed), StandardCharsets.UTF_8).equals("PONG"),
                "authenticated UDP packet was rejected");
        require(new String(sendUdp(socket, port, signed), StandardCharsets.UTF_8)
                        .equals("ERROR: replay rejected"),
                "replayed UDP packet was accepted");
    }

    private static byte[] sendUdp(DatagramSocket socket, int port, byte[] request) throws Exception {
        socket.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
        byte[] buffer = new byte[256];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);
        return Arrays.copyOfRange(response.getData(), response.getOffset(), response.getOffset() + response.getLength());
    }

    private static SSLContext trustTestCertificate() throws Exception {
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
