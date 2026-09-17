package dev.barebones.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class HttpRouter implements HttpHandler {
    private final ConcurrentMap<RouteKey, HttpRouteHandler> routes = new ConcurrentHashMap<>();

    public HttpRouter register(String method, String path, HttpRouteHandler handler) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizePath(path);
        Objects.requireNonNull(handler, "Route handler must not be null");

        RouteKey key = new RouteKey(normalizedMethod, normalizedPath);
        if (routes.putIfAbsent(key, handler) != null) {
            throw new IllegalArgumentException("Route is already registered: " + normalizedMethod + " " + normalizedPath);
        }
        return this;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = normalizeMethod(exchange.getRequestMethod());
        String path = exchange.getRequestURI().getPath();
        HttpRouteHandler handler = routes.get(new RouteKey(method, path));
        if (handler != null) {
            handler.handle(exchange);
            return;
        }

        String allowedMethods = routes.keySet().stream()
                .filter(key -> key.path().equals(path))
                .map(RouteKey::method)
                .sorted()
                .collect(Collectors.joining(", "));
        if (allowedMethods.isEmpty()) {
            HttpResponses.send(exchange, 404, "Not Found\n");
            return;
        }

        exchange.getResponseHeaders().set("Allow", allowedMethods);
        HttpResponses.send(exchange, 405, "Method Not Allowed\n");
    }

    private static String normalizeMethod(String method) {
        Objects.requireNonNull(method, "HTTP method must not be null");
        if (method.isBlank()) {
            throw new IllegalArgumentException("HTTP method must not be blank");
        }
        return method.toUpperCase(Locale.ROOT);
    }

    private static String normalizePath(String path) {
        Objects.requireNonNull(path, "Route path must not be null");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Route path must start with '/': " + path);
        }
        return path;
    }

    private record RouteKey(String method, String path) {
    }
}
