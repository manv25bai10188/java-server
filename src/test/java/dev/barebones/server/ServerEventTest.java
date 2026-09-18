package dev.barebones.server;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ServerEventTest {
    private ServerEventTest() {
    }

    public static void main(String[] args) {
        formatsStructuredFieldsInStableOrder();
        escapesUntrustedValuesWithoutLogInjection();
        rejectsInvalidFieldNames();
        System.out.println("Server event tests passed");
    }

    private static void formatsStructuredFieldsInStableOrder() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("request_id", "request-1");
        fields.put("status", "200");
        ServerEvent event = new ServerEvent(
                Instant.parse("2026-01-01T00:00:00Z"),
                System.Logger.Level.INFO,
                "https_request",
                fields,
                null);

        require(event.format().equals(
                "event=\"https_request\" timestamp=\"2026-01-01T00:00:00Z\" "
                        + "request_id=\"request-1\" status=\"200\""),
                "structured event format was unexpected: " + event.format());
    }

    private static void escapesUntrustedValuesWithoutLogInjection() {
        ServerEvent event = new ServerEvent(
                Instant.parse("2026-01-01T00:00:00Z"),
                System.Logger.Level.INFO,
                "https_request",
                Map.of("path", "/unsafe\npath\t\"quoted\"\\tail"),
                null);

        String formatted = event.format();
        require(!formatted.contains("\n"), "event contained a literal newline");
        require(!formatted.contains("\t"), "event contained a literal tab");
        require(formatted.contains("/unsafe\\npath\\t\\\"quoted\\\"\\\\tail"),
                "event did not escape the untrusted value: " + formatted);
    }

    private static void rejectsInvalidFieldNames() {
        try {
            new ServerEvent(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    System.Logger.Level.INFO,
                    "https_request",
                    Map.of("invalid field", "value"),
                    null);
            throw new AssertionError("invalid field name was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected field-name validation failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
