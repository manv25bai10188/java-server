package dev.barebones.server;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public final class MessageCodecTest {
    private static final MessageCodec CODEC = new MessageCodec();

    private MessageCodecTest() {
    }

    public static void main(String[] args) throws Exception {
        roundTripsEveryMessageField();
        recognizesEncodedMessages();
        rejectsMalformedFrames();
        enforcesPayloadLimit();
        System.out.println("Message codec tests passed");
    }

    private static void roundTripsEveryMessageField() throws Exception {
        Message original = new Message(
                Message.CURRENT_PROTOCOL_VERSION,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                MessageType.DATA,
                Instant.parse("2026-01-01T12:34:56.123456789Z"),
                new byte[]{0, 1, 2, 127, -1});

        byte[] encoded = CODEC.encode(original);
        Message decoded = CODEC.decode(encoded);

        require(decoded.protocolVersion() == original.protocolVersion(), "protocol version changed");
        require(decoded.id().equals(original.id()), "message ID changed");
        require(decoded.type() == original.type(), "message type changed");
        require(decoded.timestamp().equals(original.timestamp()), "timestamp changed");
        require(Arrays.equals(decoded.payload(), original.payload()), "binary payload changed");
        require(encoded.length == MessageCodec.FIXED_HEADER_BYTES + original.payload().length,
                "encoded length was unexpected");
    }

    private static void recognizesEncodedMessages() {
        byte[] encoded = CODEC.encode(message(new byte[0]));
        require(CODEC.hasMagic(encoded), "encoded message magic was not recognized");
        require(!CODEC.hasMagic("PING".getBytes()), "legacy message was mistaken for an encoded frame");
        require(!CODEC.hasMagic(new byte[0]), "empty message was mistaken for an encoded frame");
    }

    private static void rejectsMalformedFrames() {
        byte[] valid = CODEC.encode(message(new byte[]{1, 2, 3}));

        byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        expectMalformed(badMagic, "invalid magic was accepted");

        byte[] unknownType = valid.clone();
        unknownType[24] = 99;
        expectMalformed(unknownType, "unknown type was accepted");

        expectMalformed(Arrays.copyOf(valid, MessageCodec.FIXED_HEADER_BYTES - 1),
                "truncated header was accepted");
        expectMalformed(Arrays.copyOf(valid, valid.length - 1), "truncated payload was accepted");
        expectMalformed(Arrays.copyOf(valid, valid.length + 1), "trailing data was accepted");
    }

    private static void enforcesPayloadLimit() {
        try {
            CODEC.encode(message(new byte[MessageCodec.MAX_PAYLOAD_BYTES + 1]));
            throw new AssertionError("oversized payload was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected size validation failure.
        }
    }

    private static Message message(byte[] payload) {
        return new Message(
                Message.CURRENT_PROTOCOL_VERSION,
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                MessageType.DATA,
                Instant.parse("2026-01-01T00:00:00Z"),
                payload);
    }

    private static void expectMalformed(byte[] encoded, String failureMessage) {
        try {
            CODEC.decode(encoded);
            throw new AssertionError(failureMessage);
        } catch (MalformedMessageException expected) {
            // Expected structural validation failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
