package dev.barebones.server;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ServerMetricsTest {
    private ServerMetricsTest() {
    }

    public static void main(String[] args) {
        recordsHttpsAndUdpMeasurements();
        supportsConcurrentUpdates();
        System.out.println("Server metrics tests passed");
    }

    private static void recordsHttpsAndUdpMeasurements() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ServerMetrics metrics = new ServerMetrics(clock);

        metrics.httpsStarted();
        metrics.httpsCompleted(200, "/health", 10, 20, 1_000_000_000L, null);
        metrics.httpsStarted();
        metrics.httpsCompleted(400, "/message", 30, 40, 500_000_000L, null);
        metrics.httpsStarted();
        metrics.httpsCompleted(500, "/failure", 5, 15, 250_000_000L, new IllegalStateException("failure"));
        metrics.udpStarted();
        metrics.udpCompleted("malformed_message", 41, 24, 125_000_000L, null);
        clock.advanceSeconds(5);

        String scrape = metrics.scrape();
        requireMetric(scrape, "barebones_uptime_seconds 5");
        requireMetric(scrape, "barebones_https_requests_total 3");
        requireMetric(scrape, "barebones_https_active_requests 0");
        requireMetric(scrape, "barebones_https_request_bytes_total 45");
        requireMetric(scrape, "barebones_https_response_bytes_total 75");
        requireMetric(scrape, "barebones_https_request_duration_seconds_total 1.75");
        requireMetric(scrape, "barebones_https_responses_total{status=\"200\"} 1");
        requireMetric(scrape, "barebones_https_responses_total{status=\"400\"} 1");
        requireMetric(scrape, "barebones_https_responses_total{status=\"500\"} 1");
        requireMetric(scrape, "barebones_udp_datagrams_total 1");
        requireMetric(scrape, "barebones_udp_request_duration_seconds_total 0.125");
        requireMetric(scrape, "barebones_udp_outcomes_total{outcome=\"malformed_message\"} 1");
        requireMetric(scrape, "barebones_errors_total 1");
        requireMetric(scrape, "barebones_malformed_messages_total 2");
    }

    private static void supportsConcurrentUpdates() {
        ServerMetrics metrics = new ServerMetrics();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < 100; index++) {
                executor.submit(() -> {
                    metrics.httpsStarted();
                    metrics.httpsCompleted(200, "/test", 1, 2, 1_000, null);
                });
            }
        }

        String scrape = metrics.scrape();
        requireMetric(scrape, "barebones_https_requests_total 100");
        requireMetric(scrape, "barebones_https_active_requests 0");
        requireMetric(scrape, "barebones_https_responses_total{status=\"200\"} 100");
    }

    private static void requireMetric(String scrape, String expectedLine) {
        require(scrape.lines().anyMatch(expectedLine::equals), "missing metric: " + expectedLine);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported by this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
