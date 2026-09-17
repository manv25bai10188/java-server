package dev.barebones.server;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public record ServerConfig(
        String bindAddress,
        int httpsPort,
        int udpPort,
        Path keyStorePath,
        char[] keyStorePassword) {

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
    private static final int DEFAULT_HTTPS_PORT = 8443;
    private static final int DEFAULT_UDP_PORT = 9999;
    private static final String DEFAULT_KEYSTORE_PATH = "certs/server.p12";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";

    public ServerConfig {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("Bind address must not be blank");
        }
        validatePort("HTTPS port", httpsPort);
        validatePort("UDP port", udpPort);
        Objects.requireNonNull(keyStorePath, "Key store path must not be null");
        if (keyStorePassword == null || keyStorePassword.length == 0) {
            throw new IllegalArgumentException("Key store password must not be empty");
        }
        keyStorePassword = keyStorePassword.clone();
    }

    @Override
    public char[] keyStorePassword() {
        return keyStorePassword.clone();
    }

    public static ServerConfig fromEnvironment() {
        return from(new String[0], System.getenv());
    }

    public static ServerConfig from(String[] args, Map<String, String> environment) {
        Objects.requireNonNull(args, "Arguments must not be null");
        Objects.requireNonNull(environment, "Environment must not be null");

        Map<String, String> values = new HashMap<>();
        values.put("bind-address", environmentValue(environment, "SERVER_BIND_ADDRESS", DEFAULT_BIND_ADDRESS));
        values.put("https-port", environmentValue(environment, "SERVER_HTTPS_PORT", Integer.toString(DEFAULT_HTTPS_PORT)));
        values.put("udp-port", environmentValue(environment, "SERVER_UDP_PORT", Integer.toString(DEFAULT_UDP_PORT)));
        values.put("keystore", environmentValue(environment, "SERVER_KEYSTORE_PATH", DEFAULT_KEYSTORE_PATH));
        values.put("keystore-password", environmentValue(
                environment, "SERVER_KEYSTORE_PASSWORD", DEFAULT_KEYSTORE_PASSWORD));

        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--help") || argument.equals("-h")) {
                continue;
            }
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected positional argument: " + argument);
            }

            int separator = argument.indexOf('=');
            String option = separator >= 0 ? argument.substring(2, separator) : argument.substring(2);
            if (!values.containsKey(option)) {
                throw new IllegalArgumentException("Unknown option: --" + option);
            }

            String value;
            if (separator >= 0) {
                value = argument.substring(separator + 1);
            } else {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("Missing value for --" + option);
                }
                value = args[index];
            }
            if (value.isBlank()) {
                throw new IllegalArgumentException("Value for --" + option + " must not be blank");
            }
            values.put(option, value);
        }

        return new ServerConfig(
                values.get("bind-address"),
                parsePort("HTTPS port", values.get("https-port")),
                parsePort("UDP port", values.get("udp-port")),
                parsePath(values.get("keystore")),
                values.get("keystore-password").toCharArray());
    }

    public static boolean helpRequested(String[] args) {
        for (String argument : args) {
            if (argument.equals("--help") || argument.equals("-h")) {
                return true;
            }
        }
        return false;
    }

    public static String usage() {
        return """
                Usage: java ... dev.barebones.server.Main [options]

                Options:
                  --bind-address <address>       Interface to bind (default: 0.0.0.0)
                  --https-port <port>            HTTPS port (default: 8443)
                  --udp-port <port>              UDP port (default: 9999)
                  --keystore <path>              PKCS#12 key store (default: certs/server.p12)
                  --keystore-password <password> Key store password (default: changeit)
                  -h, --help                     Show this help message

                Environment variables:
                  SERVER_BIND_ADDRESS, SERVER_HTTPS_PORT, SERVER_UDP_PORT,
                  SERVER_KEYSTORE_PATH, SERVER_KEYSTORE_PASSWORD

                Command-line options override environment variables.
                Prefer SERVER_KEYSTORE_PASSWORD over the command-line password option.
                """;
    }

    private static String environmentValue(Map<String, String> environment, String name, String fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int parsePort(String label, String value) {
        try {
            int port = Integer.parseInt(value);
            validatePort(label, port);
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number: " + value, exception);
        }
    }

    private static void validatePort(String label, int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(label + " must be between 1 and 65535: " + port);
        }
    }

    private static Path parsePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid key store path: " + value, exception);
        }
    }
}

