package dev.barebones.server;

import java.util.Objects;

public final class SystemServerEventLogger implements ServerEventLogger {
    private final System.Logger logger;

    public SystemServerEventLogger(String loggerName) {
        this(System.getLogger(Objects.requireNonNull(loggerName, "Logger name must not be null")));
    }

    SystemServerEventLogger(System.Logger logger) {
        this.logger = Objects.requireNonNull(logger, "System logger must not be null");
    }

    @Override
    public void log(ServerEvent event) {
        Objects.requireNonNull(event, "Server event must not be null");
        if (event.error() == null) {
            logger.log(event.level(), event.format());
        } else {
            logger.log(event.level(), event.format(), event.error());
        }
    }
}
