package dev.barebones.server;

@FunctionalInterface
public interface ServerEventLogger {
    void log(ServerEvent event);
}
