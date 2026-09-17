package dev.barebones.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class MessageCodec {
    public static final String CONTENT_TYPE = "application/vnd.barebones.message";
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int FIXED_HEADER_BYTES = 41;
    public static final int MAX_ENCODED_MESSAGE_BYTES = FIXED_HEADER_BYTES + MAX_PAYLOAD_BYTES;

    private static final int MAGIC = 0x424A5331; // BJS1

    public byte[] encode(Message message) {
        Objects.requireNonNull(message, "Message must not be null");
        byte[] payload = message.payload();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Message payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(FIXED_HEADER_BYTES + payload.length);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(message.protocolVersion());
            output.writeLong(message.id().getMostSignificantBits());
            output.writeLong(message.id().getLeastSignificantBits());
            output.writeByte(message.type().wireCode());
            output.writeLong(message.timestamp().getEpochSecond());
            output.writeInt(message.timestamp().getNano());
            output.writeInt(payload.length);
            output.write(payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode in-memory message", exception);
        }
        return bytes.toByteArray();
    }

    public Message decode(byte[] encoded) throws MalformedMessageException {
        Objects.requireNonNull(encoded, "Encoded message must not be null");
        if (encoded.length > MAX_ENCODED_MESSAGE_BYTES) {
            throw new MalformedMessageException("Encoded message exceeds " + MAX_ENCODED_MESSAGE_BYTES + " bytes");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new MalformedMessageException("Invalid message magic; expected BJS1");
            }

            int protocolVersion = input.readInt();
            UUID id = new UUID(input.readLong(), input.readLong());
            MessageType type = MessageType.fromWireCode(input.readUnsignedByte());
            Instant timestamp = Instant.ofEpochSecond(input.readLong(), input.readInt());
            int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new MalformedMessageException("Invalid payload length: " + payloadLength);
            }
            if (input.available() != payloadLength) {
                throw new MalformedMessageException(
                        input.available() < payloadLength ? "Truncated message payload" : "Trailing message data");
            }

            byte[] payload = input.readNBytes(payloadLength);
            return new Message(protocolVersion, id, type, timestamp, payload);
        } catch (EOFException exception) {
            throw new MalformedMessageException("Truncated message header", exception);
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new MalformedMessageException("Invalid message field: " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new MalformedMessageException("Could not decode message", exception);
        }
    }

    public boolean hasMagic(byte[] encoded) {
        return encoded != null
                && encoded.length >= Integer.BYTES
                && encoded[0] == 'B'
                && encoded[1] == 'J'
                && encoded[2] == 'S'
                && encoded[3] == '1';
    }
}
