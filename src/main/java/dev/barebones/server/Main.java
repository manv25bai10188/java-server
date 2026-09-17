package dev.barebones.server;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromEnvironment();
        DualProtocolServer server = new DualProtocolServer(config);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));

        try {
            server.start();
            server.awaitShutdown();
        } catch (Exception exception) {
            System.err.println("Server failed: " + exception.getMessage());
            server.close();
            System.exit(1);
        }
    }
}

