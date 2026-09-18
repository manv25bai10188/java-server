package dev.barebones.server;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class ReplayProtectorTest {
    private ReplayProtectorTest() {
    }

    public static void main(String[] args) {
        rejectsStaleDuplicateAndExcessClaims();
        System.out.println("Replay protector tests passed");
    }

    private static void rejectsStaleDuplicateAndExcessClaims() {
        long now = 1_800_000_000L;
        ReplayProtector protector = new ReplayProtector(
                30, 1, Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));

        require(protector.claim("https", "nonce-0000000001", now) == ReplayProtector.Result.ACCEPTED,
                "first nonce was rejected");
        require(protector.claim("https", "nonce-0000000001", now) == ReplayProtector.Result.REPLAYED,
                "duplicate nonce was accepted");
        require(protector.claim("https", "nonce-0000000002", now)
                        == ReplayProtector.Result.CAPACITY_EXCEEDED,
                "full replay store accepted another nonce");
        require(protector.claim("udp", "nonce-0000000001", now)
                        == ReplayProtector.Result.CAPACITY_EXCEEDED,
                "scope unexpectedly bypassed the global capacity bound");
        require(protector.claim("https", "nonce-0000000003", now - 31)
                        == ReplayProtector.Result.STALE_TIMESTAMP,
                "stale timestamp was accepted");
        require(protector.trackedEntries() == 1, "unexpected number of replay entries");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
