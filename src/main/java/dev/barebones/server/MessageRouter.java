package dev.barebones.server;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MessageRouter {
    private final ConcurrentMap<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();

    public MessageRouter register(MessageType type, MessageHandler handler) {
        Objects.requireNonNull(type, "Message type must not be null");
        Objects.requireNonNull(handler, "Message handler must not be null");
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalArgumentException("Handler is already registered for message type: " + type);
        }
        return this;
    }

    public Message route(Message request) {
        Objects.requireNonNull(request, "Request must not be null");
        MessageHandler handler = handlers.get(request.type());
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for message type: " + request.type());
        }
        Message response = handler.handle(request);
        return Objects.requireNonNull(response, "Message handler must not return null");
    }
}
