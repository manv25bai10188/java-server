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
        char[] keyStorePassword,
        int maxConcurrentHttps,
        int maxConcurrentUdp,
        int rateLimitCapacity,
        int rateLimitRefillPerSecond,
        int rateLimitMaxClients) {

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";
    private static final int DEFAULT_HTTPS_PORT = 8443;
    private static final int DEFAULT_UDP_PORT = 9999;
    private static final String DEFAULT_KEYSTORE_PATH = "certs/server.p12";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";
    private static final int DEFAULT_MAX_CONCURRENT_HTTPS = 256;
    private static final int DEFAULT_MAX_CONCURRENT_UDP = 256;
    private static final int DEFAULT_RATE_LIMIT_CAPACITY = 100;
    private static final int DEFAULT_RATE_LIMIT_REFILL_PER_SECOND = 50;
    private static final int DEFAULT_RATE_LIMIT_MAX_CLIENTS = 10_000;

    public ServerConfig(
            String bindAddress,
            int httpsPort,
            int udpPort,
            Path keyStorePath,
            char[] keyStorePassword) {
        this(bindAddress, httpsPort, udpPort, keyStorePath, keyStorePassword,
                DEFAULT_MAX_CONCURRENT_HTTPS,
                DEFAULT_MAX_CONCURRENT_UDP,
                DEFAULT_RATE_LIMIT_CAPACITY,
                DEFAULT_RATE_LIMIT_REFILL_PER_SECOND,
                DEFAULT_RATE_LIMIT_MAX_CLIENTS);
    }

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
        validateLimit("Maximum concurrent HTTPS requests", maxConcurrentHttps);
        validateLimit("Maximum concurrent UDP tasks", maxConcurrentUdp);
        validateLimit("Rate-limit capacity", rateLimitCapacity);
        validateLimit("Rate-limit refill per second", rateLimitRefillPerSecond);
        validateLimit("Rate-limit maximum clients", rateLimitMaxClients);
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
        values.put("max-concurrent-https", environmentValue(
                environment, "SERVER_MAX_CONCURRENT_HTTPS", Integer.toString(DEFAULT_MAX_CONCURRENT_HTTPS)));
        values.put("max-concurrent-udp", environmentValue(
                environment, "SERVER_MAX_CONCURRENT_UDP", Integer.toString(DEFAULT_MAX_CONCURRENT_UDP)));
        values.put("rate-limit-capacity", environmentValue(
                environment, "SERVER_RATE_LIMIT_CAPACITY", Integer.toString(DEFAULT_RATE_LIMIT_CAPACITY)));
        values.put("rate-limit-refill-per-second", environmentValue(
                environment, "SERVER_RATE_LIMIT_REFILL_PER_SECOND",
                Integer.toString(DEFAULT_RATE_LIMIT_REFILL_PER_SECOND)));
        values.put("rate-limit-max-clients", environmentValue(
                environment, "SERVER_RATE_LIMIT_MAX_CLIENTS", Integer.toString(DEFAULT_RATE_LIMIT_MAX_CLIENTS)));

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
                values.get("keystore-password").toCharArray(),
                parseLimit("Maximum concurrent HTTPS requests", values.get("max-concurrent-https")),
                parseLimit("Maximum concurrent UDP tasks", values.get("max-concurrent-udp")),
                parseLimit("Rate-limit capacity", values.get("rate-limit-capacity")),
                parseLimit("Rate-limit refill per second", values.get("rate-limit-refill-per-second")),
                parseLimit("Rate-limit maximum clients", values.get("rate-limit-max-clients")));
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
                  --max-concurrent-https <count> Maximum active HTTPS requests (default: 256)
                  --max-concurrent-udp <count>   Maximum active UDP tasks (default: 256)
                  --rate-limit-capacity <count>  Per-client burst capacity (default: 100)
                  --rate-limit-refill-per-second <count>
                                                 Tokens restored per second (default: 50)
                  --rate-limit-max-clients <count>
                                                 Maximum tracked client buckets (default: 10000)
                  -h, --help                     Show this help message

                Environment variables:
                  SERVER_BIND_ADDRESS, SERVER_HTTPS_PORT, SERVER_UDP_PORT,
                  SERVER_KEYSTORE_PATH, SERVER_KEYSTORE_PASSWORD,
                  SERVER_MAX_CONCURRENT_HTTPS, SERVER_MAX_CONCURRENT_UDP,
                  SERVER_RATE_LIMIT_CAPACITY, SERVER_RATE_LIMIT_REFILL_PER_SECOND,
                  SERVER_RATE_LIMIT_MAX_CLIENTS

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

    private static int parseLimit(String label, String value) {
        try {
            int parsed = Integer.parseInt(value);
            validateLimit(label, parsed);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a number: " + value, exception);
        }
    }

    private static void validateLimit(String label, int value) {
        if (value < 1 || value > 1_000_000) {
            throw new IllegalArgumentException(label + " must be between 1 and 1000000: " + value);
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
