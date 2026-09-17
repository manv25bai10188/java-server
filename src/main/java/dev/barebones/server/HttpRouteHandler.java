package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

@FunctionalInterface
public interface HttpRouteHandler {
    void handle(HttpExchange exchange) throws IOException;
}
