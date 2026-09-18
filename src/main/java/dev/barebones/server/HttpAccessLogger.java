package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class HttpAccessLogger implements HttpHandler {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final HttpHandler next;
    private final ServerEventLogger eventLogger;
    private final ServerMetrics metrics;
    private final Clock clock;
    private final Supplier<UUID> requestIds;
    private final LongSupplier nanoTime;

    public HttpAccessLogger(HttpHandler next, ServerEventLogger eventLogger) {
        this(next, eventLogger, new ServerMetrics());
    }

    public HttpAccessLogger(HttpHandler next, ServerEventLogger eventLogger, ServerMetrics metrics) {
        this(next, eventLogger, metrics, Clock.systemUTC(), UUID::randomUUID, System::nanoTime);
    }

    HttpAccessLogger(
            HttpHandler next,
            ServerEventLogger eventLogger,
            Clock clock,
            Supplier<UUID> requestIds,
            LongSupplier nanoTime) {
        this(next, eventLogger, new ServerMetrics(clock), clock, requestIds, nanoTime);
    }

    HttpAccessLogger(
            HttpHandler next,
            ServerEventLogger eventLogger,
            ServerMetrics metrics,
            Clock clock,
            Supplier<UUID> requestIds,
            LongSupplier nanoTime) {
        this.next = Objects.requireNonNull(next, "Next HTTP handler must not be null");
        this.eventLogger = Objects.requireNonNull(eventLogger, "Event logger must not be null");
        this.metrics = Objects.requireNonNull(metrics, "Server metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        this.requestIds = Objects.requireNonNull(requestIds, "Request ID supplier must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "Nanosecond clock must not be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestId = requestIds.get().toString();
        exchange.getResponseHeaders().set(REQUEST_ID_HEADER, requestId);
        long startedAt = nanoTime.getAsLong();
        Throwable failure = null;
        metrics.httpsStarted();

        try {
            next.handle(exchange);
        } catch (IOException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            int status = HttpExchangeTelemetry.responseStatus(exchange);
            int requestBytes = HttpExchangeTelemetry.requestBytes(exchange);
            int responseBytes = HttpExchangeTelemetry.responseBytes(exchange);
            long durationNanos = Math.max(0, nanoTime.getAsLong() - startedAt);
            metrics.httpsCompleted(
                    status, exchange.getRequestURI().getPath(), requestBytes, responseBytes, durationNanos, failure);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("request_id", requestId);
            fields.put("remote", remoteAddress(exchange.getRemoteAddress()));
            fields.put("method", exchange.getRequestMethod());
            fields.put("path", exchange.getRequestURI().getPath());
            fields.put("status", Integer.toString(status));
            fields.put("request_bytes", Integer.toString(requestBytes));
            fields.put("response_bytes", Integer.toString(responseBytes));
            fields.put("duration_us", Long.toString(TimeUnit.NANOSECONDS.toMicros(durationNanos)));
            putIfPresent(fields, "message_id", HttpExchangeTelemetry.messageId(exchange));
            putIfPresent(fields, "message_type", HttpExchangeTelemetry.messageType(exchange));
            ServerEventLoggers.emit(eventLogger, new ServerEvent(
                    clock.instant(),
                    failure == null ? System.Logger.Level.INFO : System.Logger.Level.ERROR,
                    "https_request",
                    fields,
                    failure));
        }
    }

    private static String remoteAddress(InetSocketAddress address) {
        if (address == null) {
            return "unknown";
        }
        String host = address.getAddress() == null
                ? address.getHostString()
                : address.getAddress().getHostAddress();
        return host + ":" + address.getPort();
    }

    private static void putIfPresent(Map<String, String> fields, String name, String value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
