package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;

final class HttpExchangeTelemetry {
    private static final String PREFIX = HttpExchangeTelemetry.class.getName() + ".";
    private static final String RESPONSE_STATUS = PREFIX + "responseStatus";
    private static final String RESPONSE_BYTES = PREFIX + "responseBytes";
    private static final String REQUEST_BYTES = PREFIX + "requestBytes";
    private static final String MESSAGE_ID = PREFIX + "messageId";
    private static final String MESSAGE_TYPE = PREFIX + "messageType";
    private static final String ADMISSION = PREFIX + "admission";
    private static final String AUTHENTICATION = PREFIX + "authentication";
    private static final String REQUEST_BODY = PREFIX + "requestBody";

    private HttpExchangeTelemetry() {
    }

    static void reset(HttpExchange exchange) {
        exchange.setAttribute(RESPONSE_STATUS, null);
        exchange.setAttribute(RESPONSE_BYTES, null);
        exchange.setAttribute(REQUEST_BYTES, null);
        exchange.setAttribute(MESSAGE_ID, null);
        exchange.setAttribute(MESSAGE_TYPE, null);
        exchange.setAttribute(ADMISSION, null);
        exchange.setAttribute(AUTHENTICATION, null);
        exchange.setAttribute(REQUEST_BODY, null);
    }

    static void recordResponse(HttpExchange exchange, int status, int bytes) {
        exchange.setAttribute(RESPONSE_STATUS, status);
        exchange.setAttribute(RESPONSE_BYTES, bytes);
    }

    static void recordRequestBytes(HttpExchange exchange, int bytes) {
        exchange.setAttribute(REQUEST_BYTES, bytes);
    }

    static void recordMessage(HttpExchange exchange, Message message) {
        exchange.setAttribute(MESSAGE_ID, message.id().toString());
        exchange.setAttribute(MESSAGE_TYPE, message.type().name());
    }

    static void recordAdmission(HttpExchange exchange, String outcome) {
        exchange.setAttribute(ADMISSION, outcome);
    }

    static void recordAuthentication(HttpExchange exchange, String outcome) {
        exchange.setAttribute(AUTHENTICATION, outcome);
    }

    static void recordRequestBody(HttpExchange exchange, byte[] body) {
        exchange.setAttribute(REQUEST_BODY, body.clone());
        recordRequestBytes(exchange, body.length);
    }

    static int responseStatus(HttpExchange exchange) {
        return integerAttribute(exchange, RESPONSE_STATUS, 500);
    }

    static int responseBytes(HttpExchange exchange) {
        return integerAttribute(exchange, RESPONSE_BYTES, 0);
    }

    static int requestBytes(HttpExchange exchange) {
        Object recorded = exchange.getAttribute(REQUEST_BYTES);
        if (recorded instanceof Integer bytes) {
            return bytes;
        }
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength == null) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(contentLength);
            return parsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) parsed);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static String messageId(HttpExchange exchange) {
        return stringAttribute(exchange, MESSAGE_ID);
    }

    static String messageType(HttpExchange exchange) {
        return stringAttribute(exchange, MESSAGE_TYPE);
    }

    static String admission(HttpExchange exchange) {
        return stringAttribute(exchange, ADMISSION);
    }

    static String authentication(HttpExchange exchange) {
        return stringAttribute(exchange, AUTHENTICATION);
    }

    static byte[] requestBody(HttpExchange exchange) {
        Object value = exchange.getAttribute(REQUEST_BODY);
        return value instanceof byte[] bytes ? bytes.clone() : null;
    }

    private static int integerAttribute(HttpExchange exchange, String name, int fallback) {
        Object value = exchange.getAttribute(name);
        return value instanceof Integer integer ? integer : fallback;
    }

    private static String stringAttribute(HttpExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value instanceof String string ? string : null;
    }
}
