package dev.barebones.server;

@FunctionalInterface
public interface MessageHandler {
    Message handle(Message request);
}
