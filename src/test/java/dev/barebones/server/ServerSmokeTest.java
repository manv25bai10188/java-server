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

public final class ServerSmokeTest {
    private ServerSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 18_443, 19_999, Path.of("certs/server.p12"), "changeit".toCharArray());

        try (DualProtocolServer server = new DualProtocolServer(config)) {
            server.start();
            verifyHttps(config.httpsPort());
            verifyUdp(config.udpPort());
        }

        System.out.println("HTTPS and UDP smoke tests passed");
    }

    private static void verifyHttps(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustTestCertificate()).build();

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        require(health.statusCode() == 200, "health status was " + health.statusCode());
        require(health.body().contains("\"status\":\"ok\""), "health response was unexpected");

        HttpResponse<String> echo = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/echo"))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("hello over https"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(echo.statusCode() == 200, "echo status was " + echo.statusCode());
        require(echo.body().equals("hello over https"), "echo response was unexpected");

        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/missing")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        require(missing.statusCode() == 404, "missing route status was " + missing.statusCode());
    }

    private static void verifyUdp(int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3_000);
            requireUdpResponse(socket, port, "PING", "PONG");
            requireUdpResponse(socket, port, "hello over udp", "ACK: hello over udp");
        }
    }

    private static void requireUdpResponse(
            DatagramSocket socket, int port, String requestText, String expectedResponse) throws Exception {
        byte[] request = requestText.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));

        byte[] buffer = new byte[64];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);
        String body = new String(
                response.getData(), response.getOffset(), response.getLength(), StandardCharsets.UTF_8);
        require(body.equals(expectedResponse), "UDP response was unexpected: " + body);
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
