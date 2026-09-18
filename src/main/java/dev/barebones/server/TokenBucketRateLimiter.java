package dev.barebones.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class TokenBucketRateLimiter {
    private final int capacity;
    private final int refillPerSecond;
    private final int maxClients;
    private final long fullRefillNanos;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public TokenBucketRateLimiter(int capacity, int refillPerSecond, int maxClients) {
        this(capacity, refillPerSecond, maxClients, System::nanoTime);
    }

    TokenBucketRateLimiter(int capacity, int refillPerSecond, int maxClients, LongSupplier nanoTime) {
        this.capacity = positive("Capacity", capacity);
        this.refillPerSecond = positive("Refill per second", refillPerSecond);
        this.maxClients = positive("Maximum clients", maxClients);
        this.nanoTime = Objects.requireNonNull(nanoTime, "Nanosecond clock must not be null");
        this.fullRefillNanos = divideRoundingUp(
                Math.multiplyExact((long) capacity, TimeUnit.SECONDS.toNanos(1)), refillPerSecond);
    }

    public synchronized Decision check(String clientKey) {
        Objects.requireNonNull(clientKey, "Client key must not be null");
        long now = nanoTime.getAsLong();
        Bucket bucket = buckets.get(clientKey);
        if (bucket == null) {
            if (buckets.size() >= maxClients) {
                removeIdleBuckets(now);
            }
            if (buckets.size() >= maxClients) {
                return Decision.rejected(1);
            }
            bucket = new Bucket(capacity, now);
            buckets.put(clientKey, bucket);
        }

        refill(bucket, now);
        bucket.lastSeenNanos = now;
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return Decision.granted();
        }

        double missingTokens = 1.0 - bucket.tokens;
        long retryNanos = (long) Math.ceil(missingTokens * TimeUnit.SECONDS.toNanos(1) / refillPerSecond);
        return Decision.rejected(Math.max(1, divideRoundingUp(retryNanos, TimeUnit.SECONDS.toNanos(1))));
    }

    synchronized int trackedClients() {
        return buckets.size();
    }

    private void refill(Bucket bucket, long now) {
        long elapsedNanos = Math.max(0, now - bucket.lastRefillNanos);
        if (elapsedNanos == 0) {
            return;
        }
        double restored = (double) elapsedNanos * refillPerSecond / TimeUnit.SECONDS.toNanos(1);
        bucket.tokens = Math.min(capacity, bucket.tokens + restored);
        bucket.lastRefillNanos = now;
    }

    private void removeIdleBuckets(long now) {
        Iterator<Bucket> iterator = buckets.values().iterator();
        while (iterator.hasNext()) {
            Bucket bucket = iterator.next();
            if (Math.max(0, now - bucket.lastSeenNanos) >= fullRefillNanos) {
                iterator.remove();
            }
        }
    }

    private static int positive(String label, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(label + " must be positive: " + value);
        }
        return value;
    }

    private static long divideRoundingUp(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
        private static Decision granted() {
            return new Decision(true, 0);
        }

        private static Decision rejected(long retryAfterSeconds) {
            return new Decision(false, retryAfterSeconds);
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastSeenNanos;

        private Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
            this.lastSeenNanos = now;
        }
    }
}
