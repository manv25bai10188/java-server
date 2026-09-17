package dev.barebones.server;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Message(
        int protocolVersion,
        UUID id,
        MessageType type,
        Instant timestamp,
        byte[] payload) {

    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public Message {
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("Protocol version must be positive");
        }
        Objects.requireNonNull(id, "Message ID must not be null");
        Objects.requireNonNull(type, "Message type must not be null");
        Objects.requireNonNull(timestamp, "Message timestamp must not be null");
        Objects.requireNonNull(payload, "Message payload must not be null");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    public static Message request(MessageType type, byte[] payload) {
        return new Message(CURRENT_PROTOCOL_VERSION, UUID.randomUUID(), type, Instant.now(), payload);
    }
}
