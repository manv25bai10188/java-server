package dev.barebones.server;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class ServerMetrics {
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final Clock clock;
    private final Instant startedAt;
    private final LongAdder httpsRequests = new LongAdder();
    private final AtomicInteger httpsActive = new AtomicInteger();
    private final LongAdder httpsRequestBytes = new LongAdder();
    private final LongAdder httpsResponseBytes = new LongAdder();
    private final LongAdder httpsDurationNanos = new LongAdder();
    private final ConcurrentNavigableMap<Integer, LongAdder> httpsStatuses = new ConcurrentSkipListMap<>();
    private final LongAdder udpRequests = new LongAdder();
    private final AtomicInteger udpActive = new AtomicInteger();
    private final LongAdder udpRequestBytes = new LongAdder();
    private final LongAdder udpResponseBytes = new LongAdder();
    private final LongAdder udpDurationNanos = new LongAdder();
    private final ConcurrentNavigableMap<String, LongAdder> udpOutcomes = new ConcurrentSkipListMap<>();
    private final LongAdder errors = new LongAdder();
    private final LongAdder malformedMessages = new LongAdder();

    public ServerMetrics() {
        this(Clock.systemUTC());
    }

    ServerMetrics(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Metrics clock must not be null");
        this.startedAt = clock.instant();
    }

    void httpsStarted() {
        httpsRequests.increment();
        httpsActive.incrementAndGet();
    }

    void httpsCompleted(
            int status,
            String path,
            int requestBytes,
            int responseBytes,
            long durationNanos,
            Throwable failure) {
        httpsActive.decrementAndGet();
        httpsRequestBytes.add(nonNegative(requestBytes));
        httpsResponseBytes.add(nonNegative(responseBytes));
        httpsDurationNanos.add(nonNegative(durationNanos));
        httpsStatuses.computeIfAbsent(status, ignored -> new LongAdder()).increment();
        if (failure != null || status >= 500) {
            errors.increment();
        }
        if (status == 400 && "/message".equals(path)) {
            malformedMessages.increment();
        }
    }

    void udpStarted() {
        udpRequests.increment();
        udpActive.incrementAndGet();
    }

    void udpCompleted(
            String outcome,
            int requestBytes,
            int responseBytes,
            long durationNanos,
            Throwable failure) {
        udpActive.decrementAndGet();
        udpRequestBytes.add(nonNegative(requestBytes));
        udpResponseBytes.add(nonNegative(responseBytes));
        udpDurationNanos.add(nonNegative(durationNanos));
        udpOutcomes.computeIfAbsent(Objects.requireNonNull(outcome, "UDP outcome must not be null"),
                ignored -> new LongAdder()).increment();
        if (failure != null || outcome.equals("internal_error")) {
            errors.increment();
        }
        if (outcome.equals("malformed_message")) {
            malformedMessages.increment();
        }
    }

    public String scrape() {
        StringBuilder output = new StringBuilder(2_048);
        metric(output, "barebones_uptime_seconds", "Seconds since the server metrics were created", "gauge",
                Long.toString(uptimeSeconds()));
        metric(output, "barebones_https_requests_total", "HTTPS requests accepted", "counter",
                Long.toString(httpsRequests.sum()));
        metric(output, "barebones_https_active_requests", "HTTPS requests currently executing", "gauge",
                Integer.toString(httpsActive.get()));
        metric(output, "barebones_https_request_bytes_total", "HTTPS request body bytes read", "counter",
                Long.toString(httpsRequestBytes.sum()));
        metric(output, "barebones_https_response_bytes_total", "HTTPS response body bytes written", "counter",
                Long.toString(httpsResponseBytes.sum()));
        metric(output, "barebones_https_request_duration_seconds_total",
                "Cumulative HTTPS request execution time", "counter", seconds(httpsDurationNanos.sum()));
        typedHeader(output, "barebones_https_responses_total", "HTTPS responses by status", "counter");
        httpsStatuses.forEach((status, count) -> output.append("barebones_https_responses_total{status=\"")
                .append(status).append("\"} ").append(count.sum()).append('\n'));

        metric(output, "barebones_udp_datagrams_total", "UDP datagrams accepted", "counter",
                Long.toString(udpRequests.sum()));
        metric(output, "barebones_udp_active_requests", "UDP datagrams currently executing", "gauge",
                Integer.toString(udpActive.get()));
        metric(output, "barebones_udp_request_bytes_total", "UDP request bytes received", "counter",
                Long.toString(udpRequestBytes.sum()));
        metric(output, "barebones_udp_response_bytes_total", "UDP response bytes sent", "counter",
                Long.toString(udpResponseBytes.sum()));
        metric(output, "barebones_udp_request_duration_seconds_total",
                "Cumulative UDP request execution time", "counter", seconds(udpDurationNanos.sum()));
        typedHeader(output, "barebones_udp_outcomes_total", "UDP responses by outcome", "counter");
        udpOutcomes.forEach((outcome, count) -> output.append("barebones_udp_outcomes_total{outcome=\"")
                .append(escapeLabel(outcome)).append("\"} ").append(count.sum()).append('\n'));

        metric(output, "barebones_errors_total", "Internal HTTPS and UDP processing errors", "counter",
                Long.toString(errors.sum()));
        metric(output, "barebones_malformed_messages_total", "Malformed binary messages received", "counter",
                Long.toString(malformedMessages.sum()));
        return output.toString();
    }

    private long uptimeSeconds() {
        Duration uptime = Duration.between(startedAt, clock.instant());
        return Math.max(0, uptime.getSeconds());
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static String seconds(long nanoseconds) {
        return BigDecimal.valueOf(nonNegative(nanoseconds), 9).stripTrailingZeros().toPlainString();
    }

    private static void metric(
            StringBuilder output, String name, String help, String type, String value) {
        typedHeader(output, name, help, type);
        output.append(name).append(' ').append(value).append('\n');
    }

    private static void typedHeader(StringBuilder output, String name, String help, String type) {
        output.append("# HELP ").append(name).append(' ').append(help).append('\n');
        output.append("# TYPE ").append(name).append(' ').append(type.toLowerCase(Locale.ROOT)).append('\n');
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
