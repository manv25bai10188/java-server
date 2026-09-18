package dev.barebones.server;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ServerEvent(
        Instant timestamp,
        System.Logger.Level level,
        String name,
        Map<String, String> fields,
        Throwable error) {

    public ServerEvent {
        Objects.requireNonNull(timestamp, "Event timestamp must not be null");
        Objects.requireNonNull(level, "Event level must not be null");
        if (name == null || !name.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid event name: " + name);
        }
        Objects.requireNonNull(fields, "Event fields must not be null");
        LinkedHashMap<String, String> copiedFields = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid event field name: " + key);
            }
            copiedFields.put(key, Objects.requireNonNull(value, "Event field value must not be null"));
        });
        fields = Collections.unmodifiableMap(copiedFields);
    }

    public String format() {
        StringBuilder line = new StringBuilder()
                .append("event=").append(quote(name))
                .append(" timestamp=").append(quote(timestamp.toString()));
        fields.forEach((key, value) -> line.append(' ').append(key).append('=').append(quote(value)));
        return line.toString();
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(codePoint)) {
                        escaped.append(String.format("\\u%04x", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.append('"').toString();
    }
}
