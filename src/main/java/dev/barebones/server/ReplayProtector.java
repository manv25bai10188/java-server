package dev.barebones.server;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class ReplayProtector {
    private final Clock clock;
    private final long windowSeconds;
    private final int maximumEntries;
    private final Map<String, Long> expirations = new HashMap<>();

    public ReplayProtector(int windowSeconds, int maximumEntries) {
        this(windowSeconds, maximumEntries, Clock.systemUTC());
    }

    ReplayProtector(int windowSeconds, int maximumEntries, Clock clock) {
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("Replay window must be positive");
        }
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Maximum replay entries must be positive");
        }
        this.windowSeconds = windowSeconds;
        this.maximumEntries = maximumEntries;
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public synchronized Result claim(String scope, String nonce, long timestampSeconds) {
        Objects.requireNonNull(scope, "Replay scope must not be null");
        Objects.requireNonNull(nonce, "Nonce must not be null");
        long now = clock.instant().getEpochSecond();
        if (timestampSeconds < now - windowSeconds || timestampSeconds > now + windowSeconds) {
            return Result.STALE_TIMESTAMP;
        }

        removeExpired(now);
        String key = scope + ':' + nonce;
        if (expirations.containsKey(key)) {
            return Result.REPLAYED;
        }
        if (expirations.size() >= maximumEntries) {
            return Result.CAPACITY_EXCEEDED;
        }

        expirations.put(key, saturatedAdd(timestampSeconds, windowSeconds));
        return Result.ACCEPTED;
    }

    synchronized int trackedEntries() {
        removeExpired(clock.instant().getEpochSecond());
        return expirations.size();
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<String, Long>> entries = expirations.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() < now) {
                entries.remove();
            }
        }
    }

    private static long saturatedAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    public enum Result {
        ACCEPTED,
        STALE_TIMESTAMP,
        REPLAYED,
        CAPACITY_EXCEEDED
    }
}
