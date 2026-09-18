package dev.barebones.server;

import java.util.Objects;

final class ServerEventLoggers {
    private static final System.Logger FALLBACK_LOGGER = System.getLogger(ServerEventLoggers.class.getName());

    private ServerEventLoggers() {
    }

    static void emit(ServerEventLogger eventLogger, ServerEvent event) {
        Objects.requireNonNull(eventLogger, "Event logger must not be null");
        Objects.requireNonNull(event, "Server event must not be null");
        try {
            eventLogger.log(event);
        } catch (RuntimeException exception) {
            FALLBACK_LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=\"event_logger_failure\" sink=\"" + eventLogger.getClass().getName() + "\"");
        }
    }
}
