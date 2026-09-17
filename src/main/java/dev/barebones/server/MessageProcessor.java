package dev.barebones.server;

import java.time.Clock;
import java.util.Objects;

public final class MessageProcessor {
    private final MessageRouter router;

    public MessageProcessor() {
        this(Clock.systemUTC());
    }

    MessageProcessor(Clock clock) {
        this(defaultRouter(Objects.requireNonNull(clock, "Clock must not be null")));
    }

    public MessageProcessor(MessageRouter router) {
        this.router = Objects.requireNonNull(router, "Message router must not be null");
    }

    public Message process(Message request) {
        Objects.requireNonNull(request, "Request must not be null");
        if (request.protocolVersion() != Message.CURRENT_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + request.protocolVersion());
        }

        return router.route(request);
    }

    private static MessageRouter defaultRouter(Clock clock) {
        return new MessageRouter()
                .register(MessageType.PING, request -> responseTo(request, MessageType.PONG, clock, new byte[0]))
                .register(MessageType.DATA, request -> responseTo(request, MessageType.ACK, clock, request.payload()));
    }

    private static Message responseTo(Message request, MessageType type, Clock clock, byte[] payload) {
        return new Message(
                request.protocolVersion(),
                request.id(),
                type,
                clock.instant(),
                payload);
    }
}
