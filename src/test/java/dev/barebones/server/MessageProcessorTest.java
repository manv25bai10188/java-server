package dev.barebones.server;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

public final class MessageProcessorTest {
    private static final Instant REQUEST_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RESPONSE_TIME = Instant.parse("2026-01-01T00:00:01Z");
    private static final UUID MESSAGE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final MessageProcessor PROCESSOR = new MessageProcessor(
            Clock.fixed(RESPONSE_TIME, ZoneOffset.UTC));

    private MessageProcessorTest() {
    }

    public static void main(String[] args) {
        convertsPingToCorrelatedPong();
        acknowledgesDataWithoutChangingPayload();
        protectsPayloadFromMutation();
        rejectsUnsupportedVersionsAndResponseTypes();
        System.out.println("Message processor tests passed");
    }

    private static void convertsPingToCorrelatedPong() {
        Message request = message(Message.CURRENT_PROTOCOL_VERSION, MessageType.PING, new byte[0]);
        Message response = PROCESSOR.process(request);

        require(response.id().equals(MESSAGE_ID), "response ID did not correlate with request");
        require(response.protocolVersion() == Message.CURRENT_PROTOCOL_VERSION, "protocol version changed");
        require(response.type() == MessageType.PONG, "PING did not produce PONG");
        require(response.timestamp().equals(RESPONSE_TIME), "response timestamp did not use processor clock");
        require(response.payload().length == 0, "PONG payload was not empty");
    }

    private static void acknowledgesDataWithoutChangingPayload() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        Message response = PROCESSOR.process(message(Message.CURRENT_PROTOCOL_VERSION, MessageType.DATA, payload));

        require(response.type() == MessageType.ACK, "DATA did not produce ACK");
        require(new String(response.payload(), StandardCharsets.UTF_8).equals("hello"), "ACK payload changed");
    }

    private static void protectsPayloadFromMutation() {
        byte[] source = "safe".getBytes(StandardCharsets.UTF_8);
        Message message = message(Message.CURRENT_PROTOCOL_VERSION, MessageType.DATA, source);
        source[0] = 'X';
        require(new String(message.payload(), StandardCharsets.UTF_8).equals("safe"), "source mutated message");

        byte[] exposed = message.payload();
        exposed[0] = 'X';
        require(new String(message.payload(), StandardCharsets.UTF_8).equals("safe"), "accessor exposed payload");
    }

    private static void rejectsUnsupportedVersionsAndResponseTypes() {
        expectFailure(() -> PROCESSOR.process(message(2, MessageType.DATA, new byte[0])),
                "unsupported version was accepted");
        expectFailure(() -> PROCESSOR.process(message(1, MessageType.ACK, new byte[0])),
                "response type was accepted as request");
    }

    private static Message message(int version, MessageType type, byte[] payload) {
        return new Message(version, MESSAGE_ID, type, REQUEST_TIME, payload);
    }

    private static void expectFailure(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
