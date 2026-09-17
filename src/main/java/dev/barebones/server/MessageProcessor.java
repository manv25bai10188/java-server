package dev.barebones.server;

import java.time.Clock;
import java.util.Objects;

public final class MessageProcessor {
    private final Clock clock;

    public MessageProcessor() {
        this(Clock.systemUTC());
    }

    MessageProcessor(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public Message process(Message request) {
        Objects.requireNonNull(request, "Request must not be null");
        if (request.protocolVersion() != Message.CURRENT_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + request.protocolVersion());
        }

        MessageType responseType;
        byte[] responsePayload;
        switch (request.type()) {
            case PING -> {
                responseType = MessageType.PONG;
                responsePayload = new byte[0];
            }
            case DATA -> {
                responseType = MessageType.ACK;
                responsePayload = request.payload();
            }
            case PONG, ACK -> throw new IllegalArgumentException(
                    "Response message type cannot be processed as a request: " + request.type());
            default -> throw new IllegalStateException("Unhandled message type: " + request.type());
        }

        return new Message(
                request.protocolVersion(),
                request.id(),
                responseType,
                clock.instant(),
                responsePayload);
    }
}
