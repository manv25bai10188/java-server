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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class ServerSmokeTest {
    private static final MessageCodec MESSAGE_CODEC = new MessageCodec();

    private ServerSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 18_443, 19_999, Path.of("certs/server.p12"), "changeit".toCharArray());
        RecordingEventLogger eventLogger = new RecordingEventLogger();

        try (DualProtocolServer server = new DualProtocolServer(config, new MessageProcessor(), eventLogger)) {
            server.start();
            verifyHttps(config.httpsPort());
            verifyUdp(config.udpPort());
            eventLogger.awaitCount("https_request", 6);
            eventLogger.awaitCount("udp_request", 3);
            verifyRecordedEvents(eventLogger.events());
        }

        System.out.println("HTTPS and UDP smoke tests passed");
    }

    private static void verifyHttps(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustTestCertificate()).build();

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/health"))
                        .header(HttpAccessLogger.REQUEST_ID_HEADER, "client-controlled")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(health.statusCode() == 200, "health status was " + health.statusCode());
        require(health.body().contains("\"status\":\"ok\""), "health response was unexpected");
        String healthRequestId = requireRequestId(health);
        require(!healthRequestId.equals("client-controlled"), "client controlled the server request ID");

        HttpResponse<String> echo = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/echo"))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("hello over https"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(echo.statusCode() == 200, "echo status was " + echo.statusCode());
        require(echo.body().equals("hello over https"), "echo response was unexpected");
        require(!requireRequestId(echo).equals(healthRequestId), "HTTPS request IDs were not unique");

        Message messageRequest = testMessage(MessageType.DATA, "binary over https".getBytes(StandardCharsets.UTF_8));
        HttpResponse<byte[]> messageResponse = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/message"))
                        .header("Content-Type", MessageCodec.CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(MESSAGE_CODEC.encode(messageRequest)))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        require(messageResponse.statusCode() == 200, "message status was " + messageResponse.statusCode());
        require(messageResponse.headers().firstValue("Content-Type").orElse("").equals(MessageCodec.CONTENT_TYPE),
                "message response content type was unexpected");
        Message decodedResponse = MESSAGE_CODEC.decode(messageResponse.body());
        require(decodedResponse.id().equals(messageRequest.id()), "HTTPS response ID did not correlate");
        require(decodedResponse.type() == MessageType.ACK, "HTTPS DATA did not produce ACK");
        require(Arrays.equals(decodedResponse.payload(), messageRequest.payload()), "HTTPS payload changed");

        HttpResponse<String> malformed = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/message"))
                        .header("Content-Type", MessageCodec.CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{'B', 'J', 'S', '1'}))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(malformed.statusCode() == 400, "malformed message status was " + malformed.statusCode());

        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/missing")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        require(missing.statusCode() == 404, "missing route status was " + missing.statusCode());

        HttpResponse<String> wrongMethod = client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/health"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        require(wrongMethod.statusCode() == 405, "wrong method status was " + wrongMethod.statusCode());
        require(wrongMethod.headers().firstValue("Allow").orElse("").equals("GET"),
                "health Allow header was unexpected");
    }

    private static void verifyUdp(int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3_000);
            requireUdpResponse(socket, port, "PING", "PONG");
            requireUdpResponse(socket, port, "hello over udp", "ACK: hello over udp");

            Message request = testMessage(MessageType.PING, new byte[0]);
            byte[] responseBytes = sendUdp(socket, port, MESSAGE_CODEC.encode(request), 2_048);
            Message response = MESSAGE_CODEC.decode(responseBytes);
            require(response.id().equals(request.id()), "UDP response ID did not correlate");
            require(response.type() == MessageType.PONG, "encoded UDP PING did not produce PONG");
        }
    }

    private static void requireUdpResponse(
            DatagramSocket socket, int port, String requestText, String expectedResponse) throws Exception {
        byte[] request = requestText.getBytes(StandardCharsets.UTF_8);
        byte[] response = sendUdp(socket, port, request, 64);
        String body = new String(response, StandardCharsets.UTF_8);
        require(body.equals(expectedResponse), "UDP response was unexpected: " + body);
    }

    private static byte[] sendUdp(DatagramSocket socket, int port, byte[] request, int responseCapacity)
            throws Exception {
        socket.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
        byte[] buffer = new byte[responseCapacity];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);
        return Arrays.copyOfRange(
                response.getData(), response.getOffset(), response.getOffset() + response.getLength());
    }

    private static Message testMessage(MessageType type, byte[] payload) {
        return new Message(
                Message.CURRENT_PROTOCOL_VERSION,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                type,
                Instant.parse("2026-01-01T00:00:00Z"),
                payload);
    }

    private static String requireRequestId(HttpResponse<?> response) {
        String requestId = response.headers().firstValue(HttpAccessLogger.REQUEST_ID_HEADER).orElseThrow(
                () -> new AssertionError("response did not include a request ID"));
        UUID.fromString(requestId);
        return requestId;
    }

    private static void verifyRecordedEvents(List<ServerEvent> events) {
        require(events.stream().noneMatch(event -> event.format().contains("hello over")),
                "request payload leaked into structured logs");
        require(events.stream().anyMatch(event -> event.name().equals("https_request")
                        && "123e4567-e89b-12d3-a456-426614174000".equals(event.fields().get("message_id"))),
                "binary HTTPS message ID was not logged");
        require(events.stream().anyMatch(event -> event.name().equals("udp_request")
                        && "123e4567-e89b-12d3-a456-426614174000".equals(event.fields().get("message_id"))),
                "binary UDP message ID was not logged");
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

    private static final class RecordingEventLogger implements ServerEventLogger {
        private final CopyOnWriteArrayList<ServerEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void log(ServerEvent event) {
            events.add(event);
        }

        List<ServerEvent> events() {
            return List.copyOf(events);
        }

        void awaitCount(String eventName, int expectedCount) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (count(eventName) < expectedCount && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            require(count(eventName) >= expectedCount,
                    "timed out waiting for " + expectedCount + " " + eventName + " events");
        }

        private long count(String eventName) {
            return events.stream().filter(event -> event.name().equals(eventName)).count();
        }
    }
}
