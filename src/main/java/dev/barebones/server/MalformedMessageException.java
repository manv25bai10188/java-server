package dev.barebones.server;

public final class MalformedMessageException extends Exception {
    private static final long serialVersionUID = 1L;

    public MalformedMessageException(String message) {
        super(message);
    }

    public MalformedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
