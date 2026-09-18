package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class AuthenticationHandler implements HttpHandler {
    private static final int MAX_BODY_BYTES = MessageCodec.MAX_ENCODED_MESSAGE_BYTES;

    private final HttpHandler next;
    private final HmacAuthenticator authenticator;

    public AuthenticationHandler(HttpHandler next, HmacAuthenticator authenticator) {
        this.next = Objects.requireNonNull(next, "Next HTTP handler must not be null");
        this.authenticator = Objects.requireNonNull(authenticator, "Authenticator must not be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!authenticator.enabled()) {
            next.handle(exchange);
            return;
        }

        byte[] body;
        try {
            body = readBody(exchange.getRequestBody());
        } catch (RequestTooLargeException exception) {
            HttpResponses.send(exchange, 413, "Request body exceeds authentication limit\n");
            return;
        }
        HttpExchangeTelemetry.recordRequestBody(exchange, body);

        String target = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null) {
            target += "?" + exchange.getRequestURI().getRawQuery();
        }
        HmacAuthenticator.Verification verification = authenticator.verifyHttp(
                exchange.getRequestMethod(),
                target,
                body,
                exchange.getRequestHeaders().getFirst(HmacAuthenticator.TIMESTAMP_HEADER),
                exchange.getRequestHeaders().getFirst(HmacAuthenticator.NONCE_HEADER),
                exchange.getRequestHeaders().getFirst(HmacAuthenticator.AUTHORIZATION_HEADER));
        HttpExchangeTelemetry.recordAuthentication(exchange, verification.result().outcome());
        if (!verification.accepted()) {
            exchange.getResponseHeaders().set("WWW-Authenticate", HmacAuthenticator.SCHEME);
            HttpResponses.send(exchange, 401, "Unauthorized\n");
            return;
        }
        next.handle(exchange);
    }

    private static byte[] readBody(InputStream input) throws IOException, RequestTooLargeException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) {
                throw new RequestTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
