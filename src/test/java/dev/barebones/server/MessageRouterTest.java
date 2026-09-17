package dev.barebones.server;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class MessageRouterTest {
    private MessageRouterTest() {
    }

    public static void main(String[] args) {
        dispatchesToRegisteredHandler();
        rejectsMissingAndDuplicateHandlers();
        rejectsNullHandlerResponse();
        System.out.println("Message router tests passed");
    }

    private static void dispatchesToRegisteredHandler() {
        Instant responseTime = Instant.parse("2026-01-01T00:00:01Z");
        MessageRouter router = new MessageRouter().register(MessageType.DATA, request -> new Message(
                request.protocolVersion(),
                request.id(),
                MessageType.ACK,
                responseTime,
                "custom response".getBytes(StandardCharsets.UTF_8)));
        MessageProcessor processor = new MessageProcessor(router);

        Message request = Message.request(MessageType.DATA, "request".getBytes(StandardCharsets.UTF_8));
        Message response = processor.process(request);
        require(response.id().equals(request.id()), "custom response ID did not correlate");
        require(response.type() == MessageType.ACK, "custom handler returned unexpected type");
        require(new String(response.payload(), StandardCharsets.UTF_8).equals("custom response"),
                "custom handler response was unexpected");
    }

    private static void rejectsMissingAndDuplicateHandlers() {
        MessageRouter router = new MessageRouter()
                .register(MessageType.PING, request -> request);
        expectFailure(() -> router.register(MessageType.PING, request -> request),
                "duplicate message handler was accepted");
        expectFailure(() -> router.route(Message.request(MessageType.DATA, new byte[0])),
                "missing message handler was ignored");
    }

    private static void rejectsNullHandlerResponse() {
        MessageRouter router = new MessageRouter().register(MessageType.DATA, request -> null);
        try {
            router.route(Message.request(MessageType.DATA, new byte[0]));
            throw new AssertionError("null handler response was accepted");
        } catch (NullPointerException expected) {
            // Expected handler contract failure.
        }
    }

    private static void expectFailure(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected routing validation failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
