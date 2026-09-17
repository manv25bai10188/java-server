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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DualProtocolServer implements AutoCloseable {
    private static final int MAX_HTTPS_BODY_BYTES = 64 * 1024;
    private static final int MAX_UDP_PACKET_BYTES = 2_048;

    private final ServerConfig config;
    private final MessageProcessor messageProcessor = new MessageProcessor();
    private final MessageCodec messageCodec = new MessageCodec();
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpsServer httpsServer;
    private DatagramSocket udpSocket;
    private Thread udpListener;

    public DualProtocolServer(ServerConfig config) {
        this.config = config;
    }

    public void start() throws IOException, GeneralSecurityException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }

        try {
            InetAddress bindAddress = InetAddress.getByName(config.bindAddress());
            startHttps(bindAddress);
            startUdp(bindAddress);
            System.out.printf("HTTPS listening on https://%s:%d%n", config.bindAddress(), config.httpsPort());
            System.out.printf("UDP listening on %s:%d%n", config.bindAddress(), config.udpPort());
        } catch (IOException | GeneralSecurityException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    public void awaitShutdown() throws InterruptedException {
        stopped.await();
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
        httpsServer.createContext("/", this::handleRoot);
        httpsServer.createContext("/health", this::handleHealth);
        httpsServer.createContext("/echo", this::handleEcho);
        httpsServer.createContext("/message", this::handleMessage);
        httpsServer.setExecutor(requestExecutor);
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
                requestExecutor.submit(() -> replyToUdp(request, requestBytes));
            } catch (SocketException exception) {
                if (running.get()) {
                    System.err.println("UDP socket error: " + exception.getMessage());
                }
            } catch (IOException exception) {
                System.err.println("Could not receive UDP packet: " + exception.getMessage());
            }
        }
    }

    private void replyToUdp(DatagramPacket request, byte[] requestBytes) {
        byte[] responseBytes = messageCodec.hasMagic(requestBytes)
                ? processEncodedUdpMessage(requestBytes)
                : processLegacyUdpMessage(requestBytes);
        DatagramPacket response = new DatagramPacket(
                responseBytes, responseBytes.length, request.getAddress(), request.getPort());
        try {
            udpSocket.send(response);
        } catch (IOException exception) {
            if (running.get()) {
                System.err.println("Could not send UDP response: " + exception.getMessage());
            }
        }
    }

    private byte[] processLegacyUdpMessage(byte[] requestBytes) {
        String requestText = new String(requestBytes, StandardCharsets.UTF_8);
        MessageType requestType = requestText.equalsIgnoreCase("PING") ? MessageType.PING : MessageType.DATA;
        Message responseMessage = messageProcessor.process(Message.request(requestType, requestBytes));
        String responseText = switch (responseMessage.type()) {
            case PONG -> "PONG";
            case ACK -> "ACK: " + new String(responseMessage.payload(), StandardCharsets.UTF_8);
            default -> throw new IllegalStateException("Unexpected UDP response type: " + responseMessage.type());
        };
        return responseText.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] processEncodedUdpMessage(byte[] requestBytes) {
        try {
            Message request = messageCodec.decode(requestBytes);
            return messageCodec.encode(messageProcessor.process(request));
        } catch (MalformedMessageException exception) {
            return "ERROR: malformed message".getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return "ERROR: invalid message".getBytes(StandardCharsets.UTF_8);
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "Not Found\n");
            return;
        }
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "Method Not Allowed\n");
            return;
        }
        send(exchange, 200, "bare-bones java server\n");
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            send(exchange, 405, "Method Not Allowed\n");
            return;
        }
        send(exchange, 200, "{\"status\":\"ok\",\"time\":\"" + Instant.now() + "\"}\n", "application/json");
    }

    private void handleEcho(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "Method Not Allowed\n");
            return;
        }

        try {
            byte[] body = readBody(exchange.getRequestBody(), MAX_HTTPS_BODY_BYTES);
            Message response = messageProcessor.process(Message.request(MessageType.DATA, body));
            send(exchange, 200, response.payload(), contentType(exchange));
        } catch (RequestTooLargeException exception) {
            send(exchange, 413, "Request body exceeds 65536 bytes\n");
        }
    }

    private void handleMessage(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            send(exchange, 405, "Method Not Allowed\n");
            return;
        }
        if (!isMessageContentType(exchange)) {
            send(exchange, 415, "Content-Type must be " + MessageCodec.CONTENT_TYPE + "\n");
            return;
        }

        try {
            byte[] body = readBody(exchange.getRequestBody(), MessageCodec.MAX_ENCODED_MESSAGE_BYTES);
            Message request = messageCodec.decode(body);
            byte[] response = messageCodec.encode(messageProcessor.process(request));
            send(exchange, 200, response, MessageCodec.CONTENT_TYPE);
        } catch (RequestTooLargeException exception) {
            send(exchange, 413, "Encoded message is too large\n");
        } catch (MalformedMessageException exception) {
            send(exchange, 400, "Malformed message: " + exception.getMessage() + "\n");
        } catch (IllegalArgumentException exception) {
            send(exchange, 422, "Invalid message: " + exception.getMessage() + "\n");
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

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var response = exchange.getResponseBody()) {
            response.write(body);
        }
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
        requestExecutor.close();
        stopped.countDown();
    }

    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
