package dev.barebones.server;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

public final class HmacAuthenticatorTest {
    private static final byte[] SECRET = "test-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_800_000_000L;

    private HmacAuthenticatorTest() {
    }

    public static void main(String[] args) {
        authenticatesHttpAndRejectsTamperingAndReplay();
        authenticatesUdpAndRejectsTamperingAndReplay();
        System.out.println("HMAC authenticator tests passed");
    }

    private static void authenticatesHttpAndRejectsTamperingAndReplay() {
        HmacAuthenticator authenticator = authenticator();
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String nonce = "http-nonce-00000001";
        String authorization = authenticator.signHttp("POST", "/echo?mode=test", NOW, nonce, body);

        HmacAuthenticator.Verification valid = authenticator.verifyHttp(
                "POST", "/echo?mode=test", body, Long.toString(NOW), nonce, authorization);
        require(valid.result() == HmacAuthenticator.Result.AUTHENTICATED, "valid HTTP signature was rejected");
        require(Arrays.equals(valid.payload(), body), "verified HTTP body changed");

        HmacAuthenticator.Verification replay = authenticator.verifyHttp(
                "POST", "/echo?mode=test", body, Long.toString(NOW), nonce, authorization);
        require(replay.result() == HmacAuthenticator.Result.REPLAYED, "HTTP replay was accepted");

        String tamperedNonce = "http-nonce-00000002";
        HmacAuthenticator.Verification tampered = authenticator.verifyHttp(
                "POST", "/echo?mode=test", "changed".getBytes(StandardCharsets.UTF_8),
                Long.toString(NOW), tamperedNonce, authorization);
        require(tampered.result() == HmacAuthenticator.Result.INVALID_SIGNATURE,
                "tampered HTTP body was accepted");

        HmacAuthenticator.Verification stale = authenticator.verifyHttp(
                "POST", "/echo?mode=test", body, Long.toString(NOW - 31), "http-nonce-00000003",
                authenticator.signHttp("POST", "/echo?mode=test", NOW - 31, "http-nonce-00000003", body));
        require(stale.result() == HmacAuthenticator.Result.STALE_TIMESTAMP, "stale HTTP signature was accepted");
    }

    private static void authenticatesUdpAndRejectsTamperingAndReplay() {
        HmacAuthenticator authenticator = authenticator();
        byte[] body = "PING".getBytes(StandardCharsets.UTF_8);
        byte[] envelope = authenticator.wrapUdp(
                body, NOW, UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));

        HmacAuthenticator.Verification valid = authenticator.verifyUdp(envelope);
        require(valid.result() == HmacAuthenticator.Result.AUTHENTICATED, "valid UDP envelope was rejected");
        require(Arrays.equals(valid.payload(), body), "verified UDP payload changed");
        require(authenticator.verifyUdp(envelope).result() == HmacAuthenticator.Result.REPLAYED,
                "UDP replay was accepted");

        byte[] tampered = envelope.clone();
        tampered[HmacAuthenticator.UDP_OVERHEAD_BYTES] ^= 1;
        require(authenticator().verifyUdp(tampered).result() == HmacAuthenticator.Result.INVALID_SIGNATURE,
                "tampered UDP envelope was accepted");
        require(authenticator().verifyUdp(body).result() == HmacAuthenticator.Result.MISSING_CREDENTIALS,
                "unsigned UDP payload was accepted");
    }

    private static HmacAuthenticator authenticator() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
        return new HmacAuthenticator(SECRET, new ReplayProtector(30, 100, clock), clock);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
