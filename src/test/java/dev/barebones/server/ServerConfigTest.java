package dev.barebones.server;

import java.nio.file.Path;
import java.util.Map;

public final class ServerConfigTest {
    private ServerConfigTest() {
    }

    public static void main(String[] args) {
        usesDefaultsWhenNoConfigurationIsProvided();
        readsEnvironmentValues();
        commandLineOverridesEnvironment();
        supportsEqualsSyntax();
        rejectsInvalidConfiguration();
        exposesHelpText();
        System.out.println("Configuration tests passed");
    }

    private static void usesDefaultsWhenNoConfigurationIsProvided() {
        ServerConfig config = ServerConfig.from(new String[0], Map.of());
        require(config.bindAddress().equals("0.0.0.0"), "unexpected default bind address");
        require(config.httpsPort() == 8443, "unexpected default HTTPS port");
        require(config.udpPort() == 9999, "unexpected default UDP port");
        require(config.keyStorePath().equals(Path.of("certs/server.p12")), "unexpected default key store");
    }

    private static void readsEnvironmentValues() {
        ServerConfig config = ServerConfig.from(new String[0], Map.of(
                "SERVER_BIND_ADDRESS", "127.0.0.1",
                "SERVER_HTTPS_PORT", "9443",
                "SERVER_UDP_PORT", "9090",
                "SERVER_KEYSTORE_PATH", "private/server.p12",
                "SERVER_KEYSTORE_PASSWORD", "environment-secret"));

        require(config.bindAddress().equals("127.0.0.1"), "environment bind address was ignored");
        require(config.httpsPort() == 9443, "environment HTTPS port was ignored");
        require(config.udpPort() == 9090, "environment UDP port was ignored");
        require(config.keyStorePath().equals(Path.of("private/server.p12")), "environment key store was ignored");
        require(new String(config.keyStorePassword()).equals("environment-secret"), "environment password was ignored");
    }

    private static void commandLineOverridesEnvironment() {
        ServerConfig config = ServerConfig.from(new String[]{
                "--bind-address", "localhost",
                "--https-port", "10443",
                "--udp-port", "10000",
                "--keystore", "cli/server.p12",
                "--keystore-password", "cli-secret"
        }, Map.of(
                "SERVER_BIND_ADDRESS", "192.0.2.1",
                "SERVER_HTTPS_PORT", "9443",
                "SERVER_UDP_PORT", "9090"));

        require(config.bindAddress().equals("localhost"), "CLI bind address did not win");
        require(config.httpsPort() == 10443, "CLI HTTPS port did not win");
        require(config.udpPort() == 10000, "CLI UDP port did not win");
        require(config.keyStorePath().equals(Path.of("cli/server.p12")), "CLI key store did not win");
        require(new String(config.keyStorePassword()).equals("cli-secret"), "CLI password did not win");
    }

    private static void supportsEqualsSyntax() {
        ServerConfig config = ServerConfig.from(
                new String[]{"--bind-address=127.0.0.1", "--https-port=7443", "--udp-port=7000"},
                Map.of());
        require(config.bindAddress().equals("127.0.0.1"), "equals bind syntax failed");
        require(config.httpsPort() == 7443, "equals HTTPS syntax failed");
        require(config.udpPort() == 7000, "equals UDP syntax failed");
    }

    private static void rejectsInvalidConfiguration() {
        expectFailure(new String[]{"--https-port", "0"}, "invalid low port was accepted");
        expectFailure(new String[]{"--udp-port", "70000"}, "invalid high port was accepted");
        expectFailure(new String[]{"--https-port", "many"}, "non-numeric port was accepted");
        expectFailure(new String[]{"--unknown", "value"}, "unknown option was accepted");
        expectFailure(new String[]{"--keystore"}, "missing option value was accepted");
        expectFailure(new String[]{"positional"}, "positional argument was accepted");
    }

    private static void exposesHelpText() {
        require(ServerConfig.helpRequested(new String[]{"--help"}), "long help option was ignored");
        require(ServerConfig.helpRequested(new String[]{"-h"}), "short help option was ignored");
        require(ServerConfig.usage().contains("--https-port"), "usage is missing HTTPS option");
        require(ServerConfig.usage().contains("SERVER_UDP_PORT"), "usage is missing environment variables");
    }

    private static void expectFailure(String[] args, String message) {
        try {
            ServerConfig.from(args, Map.of());
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
