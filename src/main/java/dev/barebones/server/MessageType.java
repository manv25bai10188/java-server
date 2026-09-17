package dev.barebones.server;

public enum MessageType {
    PING(1),
    DATA(2),
    PONG(3),
    ACK(4);

    private final int wireCode;

    MessageType(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static MessageType fromWireCode(int wireCode) {
        for (MessageType type : values()) {
            if (type.wireCode == wireCode) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type code: " + wireCode);
    }
}
