package dev.barebones.server;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (ServerConfig.helpRequested(args)) {
            System.out.print(ServerConfig.usage());
            return;
        }

        ServerConfig config;
        try {
            config = ServerConfig.from(args, System.getenv());
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.err.println();
            System.err.print(ServerConfig.usage());
            System.exit(2);
            return;
        }

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
