package dev.barebones.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class TokenBucketRateLimiterTest {
    private TokenBucketRateLimiterTest() {
    }

    public static void main(String[] args) {
        enforcesBurstAndRefillRate();
        isolatesClientsAndBoundsTracking();
        System.out.println("Token bucket rate limiter tests passed");
    }

    private static void enforcesBurstAndRefillRate() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1, 10, now::get);

        require(limiter.check("client").allowed(), "first token was rejected");
        require(limiter.check("client").allowed(), "second token was rejected");
        TokenBucketRateLimiter.Decision rejected = limiter.check("client");
        require(!rejected.allowed(), "request beyond burst capacity was accepted");
        require(rejected.retryAfterSeconds() == 1, "retry delay was unexpected");

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        require(!limiter.check("client").allowed(), "partial token was accepted");
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        require(limiter.check("client").allowed(), "refilled token was rejected");
    }

    private static void isolatesClientsAndBoundsTracking() {
        AtomicLong now = new AtomicLong();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 1, now::get);

        require(limiter.check("first").allowed(), "first client was rejected");
        require(!limiter.check("second").allowed(), "client tracking bound was ignored");
        require(limiter.trackedClients() == 1, "unexpected tracked-client count");

        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        require(limiter.check("second").allowed(), "idle client bucket was not reclaimed");
        require(limiter.trackedClients() == 1, "reclaimed bucket did not preserve bound");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
