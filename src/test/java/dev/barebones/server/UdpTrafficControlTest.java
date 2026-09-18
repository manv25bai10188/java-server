package dev.barebones.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class UdpTrafficControlTest {
    private UdpTrafficControlTest() {
    }

    public static void main(String[] args) throws Exception {
        rejectsUdpBeforeSubmittingExcessWork();
        reportsUdpConcurrencySaturation();
        System.out.println("UDP traffic control tests passed");
    }

    private static void reportsUdpConcurrencySaturation() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                18_445,
                19_997,
                Path.of("certs/server.p12"),
                "changeit".toCharArray());
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 10, 10, new AtomicLong()::get);
        TrafficController trafficController = new TrafficController(10, 1, limiter);
        TrafficController.Admission heldAdmission = trafficController.admitUdp(InetAddress.getLoopbackAddress());
        require(heldAdmission.allowed(), "could not reserve UDP concurrency permit for test");

        TrafficController.Permit heldPermit = heldAdmission.permit();
        try (DualProtocolServer server = new DualProtocolServer(
                     config, new MessageProcessor(), event -> { }, new ServerMetrics(), trafficController);
             DatagramSocket socket = new DatagramSocket()) {
            server.start();
            socket.setSoTimeout(3_000);
            require(send(socket, config.udpPort(), "PING").equals("ERROR: server busy"),
                    "overloaded UDP response was unexpected");
            awaitMetric(server.metrics(), "barebones_udp_outcomes_total{outcome=\"overloaded\"} 1");
        } finally {
            heldPermit.close();
        }
    }

    private static void rejectsUdpBeforeSubmittingExcessWork() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                18_444,
                19_998,
                Path.of("certs/server.p12"),
                "changeit".toCharArray());
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 10, new AtomicLong()::get);
        TrafficController trafficController = new TrafficController(10, 10, limiter);

        try (DualProtocolServer server = new DualProtocolServer(
                config, new MessageProcessor(), event -> { }, new ServerMetrics(), trafficController);
             DatagramSocket socket = new DatagramSocket()) {
            server.start();
            socket.setSoTimeout(3_000);
            require(send(socket, config.udpPort(), "PING").equals("PONG"), "first UDP packet was rejected");
            require(send(socket, config.udpPort(), "PING").equals("ERROR: rate limited"),
                    "rate-limited UDP response was unexpected");
            awaitMetric(server.metrics(), "barebones_udp_outcomes_total{outcome=\"rate_limited\"} 1");
        }
    }

    private static void awaitMetric(ServerMetrics metrics, String expectedLine) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (metrics.scrape().lines().anyMatch(expectedLine::equals)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("timed out waiting for metric: " + expectedLine);
    }

    private static String send(DatagramSocket socket, int port, String body) throws Exception {
        byte[] request = body.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
        byte[] buffer = new byte[64];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        socket.receive(response);
        return new String(
                Arrays.copyOfRange(response.getData(), response.getOffset(), response.getOffset() + response.getLength()),
                StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
