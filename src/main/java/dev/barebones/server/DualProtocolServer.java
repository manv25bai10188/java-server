package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DualProtocolServer implements AutoCloseable {
    private static final int MAX_HTTPS_BODY_BYTES = 64 * 1024;
    private static final int MAX_UDP_PACKET_BYTES = 2_048;

    private final ServerConfig config;
    private final MessageProcessor messageProcessor;
    private final ServerEventLogger eventLogger;
    private final ServerMetrics metrics;
    private final TrafficController trafficController;
    private final MessageCodec messageCodec = new MessageCodec();
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ExecutorService udpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final BoundedVirtualThreadExecutor httpsExecutor;
    private HttpsServer httpsServer;
    private DatagramSocket udpSocket;
    private Thread udpListener;

    public DualProtocolServer(ServerConfig config) {
        this(config, new MessageProcessor(), new SystemServerEventLogger(DualProtocolServer.class.getName()),
                new ServerMetrics());
    }

    public DualProtocolServer(ServerConfig config, MessageProcessor messageProcessor) {
        this(config, messageProcessor, new SystemServerEventLogger(DualProtocolServer.class.getName()),
                new ServerMetrics());
    }

    public DualProtocolServer(
            ServerConfig config, MessageProcessor messageProcessor, ServerEventLogger eventLogger) {
        this(config, messageProcessor, eventLogger, new ServerMetrics());
    }

    public DualProtocolServer(
            ServerConfig config,
            MessageProcessor messageProcessor,
            ServerEventLogger eventLogger,
            ServerMetrics metrics) {
        this(config, messageProcessor, eventLogger, metrics, new TrafficController(config));
    }

    DualProtocolServer(
            ServerConfig config,
            MessageProcessor messageProcessor,
            ServerEventLogger eventLogger,
            ServerMetrics metrics,
            TrafficController trafficController) {
        this.config = Objects.requireNonNull(config, "Server config must not be null");
        this.messageProcessor = Objects.requireNonNull(messageProcessor, "Message processor must not be null");
        this.eventLogger = Objects.requireNonNull(eventLogger, "Event logger must not be null");
        this.metrics = Objects.requireNonNull(metrics, "Server metrics must not be null");
        this.trafficController = Objects.requireNonNull(trafficController, "Traffic controller must not be null");
        this.httpsExecutor = new BoundedVirtualThreadExecutor(config.maxConcurrentHttps(), "https-worker-");
    }

    public void start() throws IOException, GeneralSecurityException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }

        try {
            InetAddress bindAddress = InetAddress.getByName(config.bindAddress());
            startHttps(bindAddress);
            startUdp(bindAddress);
            logEvent(System.Logger.Level.INFO, "server_started", Map.of(
                    "bind_address", config.bindAddress(),
                    "https_port", Integer.toString(config.httpsPort()),
                    "udp_port", Integer.toString(config.udpPort()),
                    "max_concurrent_https", Integer.toString(config.maxConcurrentHttps()),
                    "max_concurrent_udp", Integer.toString(config.maxConcurrentUdp())), null);
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            logEvent(System.Logger.Level.ERROR, "server_start_failed", Map.of(), exception);
            close();
            throw exception;
        }
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    public ServerMetrics metrics() {
        return metrics;
    }

    private void startHttps(InetAddress bindAddress) throws IOException, GeneralSecurityException {
        SSLContext sslContext = createSslContext();
        httpsServer = HttpsServer.create(new InetSocketAddress(bindAddress, config.httpsPort()), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
                sslParameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                parameters.setSSLParameters(sslParameters);
            }
        });
        HttpRouter router = new HttpRouter()
                .register("GET", "/", this::handleRoot)
                .register("GET", "/health", this::handleHealth)
                .register("GET", "/metrics", this::handleMetrics)
                .register("POST", "/echo", this::handleEcho)
                .register("POST", "/message", this::handleMessage);
        HttpAccessLogger accessLogger = new HttpAccessLogger(
                new TrafficControlHandler(router, trafficController), eventLogger, metrics);
        httpsServer.createContext("/", accessLogger);
        httpsServer.setExecutor(httpsExecutor);
        httpsServer.start();
    }

    private SSLContext createSslContext() throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(config.keyStorePath())) {
            keyStore.load(input, config.keyStorePassword());
        }

        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, config.keyStorePassword());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers.getKeyManagers(), null, null);
        return sslContext;
    }

    private void startUdp(InetAddress bindAddress) throws SocketException {
        udpSocket = new DatagramSocket(new InetSocketAddress(bindAddress, config.udpPort()));
        udpListener = Thread.ofVirtual().name("udp-listener").start(this::listenForUdpPackets);
    }

    private void listenForUdpPackets() {
        byte[] buffer = new byte[MAX_UDP_PACKET_BYTES];
        while (running.get()) {
            DatagramPacket request = new DatagramPacket(buffer, buffer.length);
            try {
                udpSocket.receive(request);
                byte[] requestBytes = Arrays.copyOfRange(
                        request.getData(), request.getOffset(), request.getOffset() + request.getLength());
                TrafficController.Admission admission = trafficController.admitUdp(request.getAddress());
                if (!admission.allowed()) {
                    replyToRejectedUdp(request, requestBytes, admission.rejectionReason());
                    continue;
                }
                try {
                    udpExecutor.submit(() -> {
                        try {
                            replyToUdp(request, requestBytes);
                        } finally {
                            admission.permit().close();
                        }
                    });
                } catch (RuntimeException exception) {
                    admission.permit().close();
                    replyToRejectedUdp(
                            request, requestBytes, TrafficController.RejectionReason.CONCURRENCY_LIMIT);
                }
            } catch (SocketException exception) {
                if (running.get()) {
                    logEvent(System.Logger.Level.ERROR, "udp_socket_error", Map.of(), exception);
                }
            } catch (IOException exception) {
                logEvent(System.Logger.Level.ERROR, "udp_receive_error", Map.of(), exception);
            }
        }
    }

    private void replyToUdp(DatagramPacket request, byte[] requestBytes) {
        long startedAt = System.nanoTime();
        metrics.udpStarted();
        UdpProcessingResult result;
        Throwable failure = null;
        try {
            result = messageCodec.hasMagic(requestBytes)
                    ? processEncodedUdpMessage(requestBytes)
                    : processLegacyUdpMessage(requestBytes);
        } catch (RuntimeException exception) {
            failure = exception;
            result = new UdpProcessingResult(
                    UUID.randomUUID().toString(), null, "unknown", "internal_error",
                    "ERROR: internal server error".getBytes(StandardCharsets.UTF_8));
        }

        byte[] responseBytes = result.responseBytes();
        DatagramPacket response = new DatagramPacket(
                responseBytes, responseBytes.length, request.getAddress(), request.getPort());
        try {
            udpSocket.send(response);
        } catch (IOException exception) {
            failure = exception;
            if (running.get()) {
                logEvent(System.Logger.Level.ERROR, "udp_send_error", Map.of(
                        "request_id", result.requestId(),
                        "remote", remoteAddress(request)), exception);
            }
        } finally {
            long durationNanos = Math.max(0, System.nanoTime() - startedAt);
            completeUdpRequest(
                    request,
                    result.requestId(),
                    result.messageId(),
                    result.messageType(),
                    result.outcome(),
                    requestBytes.length,
                    responseBytes.length,
                    durationNanos,
                    failure);
        }
    }

    private void replyToRejectedUdp(
            DatagramPacket request,
            byte[] requestBytes,
            TrafficController.RejectionReason rejectionReason) {
        long startedAt = System.nanoTime();
        metrics.udpStarted();
        String outcome = rejectionReason.outcome();
        byte[] responseBytes = (rejectionReason == TrafficController.RejectionReason.RATE_LIMIT
                ? "ERROR: rate limited"
                : "ERROR: server busy").getBytes(StandardCharsets.UTF_8);
        Throwable failure = null;
        try {
            udpSocket.send(new DatagramPacket(
                    responseBytes, responseBytes.length, request.getAddress(), request.getPort()));
        } catch (IOException exception) {
            failure = exception;
        } finally {
            completeUdpRequest(
                    request,
                    UUID.randomUUID().toString(),
                    null,
                    "unknown",
                    outcome,
                    requestBytes.length,
                    responseBytes.length,
                    Math.max(0, System.nanoTime() - startedAt),
                    failure);
        }
    }

    private void completeUdpRequest(
            DatagramPacket request,
            String requestId,
            String messageId,
            String messageType,
            String outcome,
            int requestBytes,
            int responseBytes,
            long durationNanos,
            Throwable failure) {
        metrics.udpCompleted(outcome, requestBytes, responseBytes, durationNanos, failure);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("request_id", requestId);
        if (messageId != null) {
            fields.put("message_id", messageId);
        }
        fields.put("remote", remoteAddress(request));
        fields.put("message_type", messageType);
        fields.put("outcome", outcome);
        fields.put("request_bytes", Integer.toString(requestBytes));
        fields.put("response_bytes", Integer.toString(responseBytes));
        fields.put("duration_us", Long.toString(TimeUnit.NANOSECONDS.toMicros(durationNanos)));
        logEvent(failure == null ? System.Logger.Level.INFO : System.Logger.Level.ERROR,
                "udp_request", fields, failure);
    }

    private UdpProcessingResult processLegacyUdpMessage(byte[] requestBytes) {
        String requestText = new String(requestBytes, StandardCharsets.UTF_8);
        MessageType requestType = requestText.equalsIgnoreCase("PING") ? MessageType.PING : MessageType.DATA;
        Message requestMessage = Message.request(requestType, requestBytes);
        Message responseMessage = messageProcessor.process(requestMessage);
        String responseText = switch (responseMessage.type()) {
            case PONG -> "PONG";
            case ACK -> "ACK: " + new String(responseMessage.payload(), StandardCharsets.UTF_8);
            default -> throw new IllegalStateException("Unexpected UDP response type: " + responseMessage.type());
        };
        return new UdpProcessingResult(
                requestMessage.id().toString(),
                null,
                requestMessage.type().name(),
                responseMessage.type().name(),
                responseText.getBytes(StandardCharsets.UTF_8));
    }

    private UdpProcessingResult processEncodedUdpMessage(byte[] requestBytes) {
        Message request;
        try {
            request = messageCodec.decode(requestBytes);
        } catch (MalformedMessageException exception) {
            return new UdpProcessingResult(
                    UUID.randomUUID().toString(), null, "unknown", "malformed_message",
                    "ERROR: malformed message".getBytes(StandardCharsets.UTF_8));
        }

        try {
            Message response = messageProcessor.process(request);
            return new UdpProcessingResult(
                    request.id().toString(),
                    request.id().toString(),
                    request.type().name(),
                    response.type().name(),
                    messageCodec.encode(response));
        } catch (IllegalArgumentException exception) {
            return new UdpProcessingResult(
                    request.id().toString(), request.id().toString(), request.type().name(), "invalid_message",
                    "ERROR: invalid message".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        HttpResponses.send(exchange, 200, "bare-bones java server\n");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        HttpResponses.send(
                exchange, 200, "{\"status\":\"ok\",\"time\":\"" + Instant.now() + "\"}\n", "application/json");
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        HttpResponses.send(exchange, 200, metrics.scrape(), ServerMetrics.CONTENT_TYPE);
    }

    private void handleEcho(HttpExchange exchange) throws IOException {
        try {
            byte[] body = readBody(exchange.getRequestBody(), MAX_HTTPS_BODY_BYTES);
            HttpExchangeTelemetry.recordRequestBytes(exchange, body.length);
            Message request = Message.request(MessageType.DATA, body);
            HttpExchangeTelemetry.recordMessage(exchange, request);
            Message response = messageProcessor.process(request);
            HttpResponses.send(exchange, 200, response.payload(), contentType(exchange));
        } catch (RequestTooLargeException exception) {
            HttpResponses.send(exchange, 413, "Request body exceeds 65536 bytes\n");
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        if (!isMessageContentType(exchange)) {
            HttpResponses.send(exchange, 415, "Content-Type must be " + MessageCodec.CONTENT_TYPE + "\n");
            return;
        }

        try {
            byte[] body = readBody(exchange.getRequestBody(), MessageCodec.MAX_ENCODED_MESSAGE_BYTES);
            HttpExchangeTelemetry.recordRequestBytes(exchange, body.length);
            Message request = messageCodec.decode(body);
            HttpExchangeTelemetry.recordMessage(exchange, request);
            byte[] response = messageCodec.encode(messageProcessor.process(request));
            HttpResponses.send(exchange, 200, response, MessageCodec.CONTENT_TYPE);
        } catch (RequestTooLargeException exception) {
            HttpResponses.send(exchange, 413, "Encoded message is too large\n");
        } catch (MalformedMessageException exception) {
            HttpResponses.send(exchange, 400, "Malformed message: " + exception.getMessage() + "\n");
        } catch (IllegalArgumentException exception) {
            HttpResponses.send(exchange, 422, "Invalid message: " + exception.getMessage() + "\n");
        }
    }

    private static byte[] readBody(InputStream input, int maximumBytes)
            throws IOException, RequestTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new RequestTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isMessageContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            return false;
        }
        int parameterStart = contentType.indexOf(';');
        String mediaType = parameterStart >= 0 ? contentType.substring(0, parameterStart) : contentType;
        return mediaType.trim().equalsIgnoreCase(MessageCodec.CONTENT_TYPE);
    }

    private static String contentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        return contentType == null ? "application/octet-stream" : contentType;
    }

    private static String remoteAddress(DatagramPacket packet) {
        return packet.getAddress().getHostAddress() + ":" + packet.getPort();
    }

    private void logEvent(
            System.Logger.Level level,
            String name,
            Map<String, String> fields,
            Throwable error) {
        ServerEventLoggers.emit(eventLogger, new ServerEvent(Instant.now(), level, name, fields, error));
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (httpsServer != null) {
            httpsServer.stop(1);
        }
        if (udpSocket != null) {
            udpSocket.close();
        }
        udpExecutor.close();
        httpsExecutor.close();
        stopped.countDown();
        logEvent(System.Logger.Level.INFO, "server_stopped", Map.of(), null);
    }

    private record UdpProcessingResult(
            String requestId,
            String messageId,
            String messageType,
            String outcome,
            byte[] responseBytes) {

        private UdpProcessingResult {
            responseBytes = responseBytes.clone();
        }

        @Override
        public byte[] responseBytes() {
            return responseBytes.clone();
        }
    }

    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
