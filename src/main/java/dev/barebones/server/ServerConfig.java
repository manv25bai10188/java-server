package dev.barebones.server;

import java.nio.file.Path;

public record ServerConfig(
        String bindAddress,
        int httpsPort,
        int udpPort,
        Path keyStorePath,
        char[] keyStorePassword) {

    public static ServerConfig fromEnvironment() {
        return new ServerConfig(
                value("SERVER_BIND_ADDRESS", "0.0.0.0"),
                port("SERVER_HTTPS_PORT", 8443),
                port("SERVER_UDP_PORT", 9999),
                Path.of(value("SERVER_KEYSTORE_PATH", "certs/server.p12")),
                value("SERVER_KEYSTORE_PASSWORD", "changeit").toCharArray());
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int port(String name, int fallback) {
        String value = value(name, Integer.toString(fallback));
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("must be between 1 and 65535");
            }
            return port;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, exception);
        }
    }
}

