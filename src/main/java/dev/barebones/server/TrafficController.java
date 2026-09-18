package dev.barebones.server;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TrafficController {
    private final Semaphore httpsPermits;
    private final Semaphore udpPermits;
    private final TokenBucketRateLimiter rateLimiter;

    public TrafficController(ServerConfig config) {
        this(
                config.maxConcurrentHttps(),
                config.maxConcurrentUdp(),
                new TokenBucketRateLimiter(
                        config.rateLimitCapacity(),
                        config.rateLimitRefillPerSecond(),
                        config.rateLimitMaxClients()));
    }

    TrafficController(int maxConcurrentHttps, int maxConcurrentUdp, TokenBucketRateLimiter rateLimiter) {
        if (maxConcurrentHttps < 1 || maxConcurrentUdp < 1) {
            throw new IllegalArgumentException("Concurrency limits must be positive");
        }
        this.httpsPermits = new Semaphore(maxConcurrentHttps);
        this.udpPermits = new Semaphore(maxConcurrentUdp);
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "Rate limiter must not be null");
    }

    public Admission admitHttps(InetAddress clientAddress) {
        return admit("https", clientAddress, httpsPermits);
    }

    public Admission admitUdp(InetAddress clientAddress) {
        return admit("udp", clientAddress, udpPermits);
    }

    private Admission admit(String protocol, InetAddress clientAddress, Semaphore permits) {
        String client = clientAddress == null ? "unknown" : clientAddress.getHostAddress();
        TokenBucketRateLimiter.Decision rateDecision = rateLimiter.check(protocol + ":" + client);
        if (!rateDecision.allowed()) {
            return Admission.rejected(RejectionReason.RATE_LIMIT, rateDecision.retryAfterSeconds());
        }
        if (!permits.tryAcquire()) {
            return Admission.rejected(RejectionReason.CONCURRENCY_LIMIT, 1);
        }
        return Admission.accepted(new Permit(permits));
    }

    public enum RejectionReason {
        RATE_LIMIT("rate_limited"),
        CONCURRENCY_LIMIT("overloaded");

        private final String outcome;

        RejectionReason(String outcome) {
            this.outcome = outcome;
        }

        public String outcome() {
            return outcome;
        }
    }

    public record Admission(Permit permit, RejectionReason rejectionReason, long retryAfterSeconds) {
        private static Admission accepted(Permit permit) {
            return new Admission(permit, null, 0);
        }

        private static Admission rejected(RejectionReason reason, long retryAfterSeconds) {
            return new Admission(null, reason, retryAfterSeconds);
        }

        public boolean allowed() {
            return permit != null;
        }
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }
    }
}
