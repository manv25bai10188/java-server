package dev.barebones.server;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class HmacAuthenticator {
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String TIMESTAMP_HEADER = "X-Barebones-Timestamp";
    public static final String NONCE_HEADER = "X-Barebones-Nonce";
    public static final String SCHEME = "HMAC-SHA256";
    public static final int UDP_OVERHEAD_BYTES = 64;

    private static final int UDP_MAGIC = 0x42484131; // BHA1
    private static final int UDP_FIXED_HEADER_BYTES = 32;
    private static final int SHA_256_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;
    private final ReplayProtector replayProtector;
    private final Clock clock;

    public HmacAuthenticator(ServerConfig config) {
        this(toUtf8(config.hmacSecret()),
                new ReplayProtector(config.authenticationWindowSeconds(),
                        config.authenticationReplayMaxEntries()), Clock.systemUTC());
    }

    HmacAuthenticator(byte[] secret, ReplayProtector replayProtector, Clock clock) {
        this.secret = Objects.requireNonNull(secret, "HMAC secret must not be null").clone();
        this.replayProtector = Objects.requireNonNull(replayProtector, "Replay protector must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public boolean enabled() {
        return secret.length > 0;
    }

    public String signHttp(String method, String requestTarget, long timestampSeconds, String nonce, byte[] body) {
        requireEnabled();
        return SCHEME + " " + HEX.formatHex(hmac(httpCanonicalBytes(
                method, requestTarget, timestampSeconds, nonce, body)));
    }

    public byte[] wrapUdp(byte[] payload, long timestampSeconds, UUID nonce) {
        requireEnabled();
        Objects.requireNonNull(payload, "UDP payload must not be null");
        Objects.requireNonNull(nonce, "UDP nonce must not be null");
        ByteBuffer unsigned = ByteBuffer.allocate(UDP_FIXED_HEADER_BYTES + payload.length);
        unsigned.putInt(UDP_MAGIC);
        unsigned.putLong(timestampSeconds);
        unsigned.putLong(nonce.getMostSignificantBits());
        unsigned.putLong(nonce.getLeastSignificantBits());
        unsigned.putInt(payload.length);
        unsigned.put(payload);
        byte[] authenticated = unsigned.array();
        ByteBuffer envelope = ByteBuffer.allocate(authenticated.length + SHA_256_BYTES);
        envelope.put(authenticated);
        envelope.put(hmac(authenticated));
        return envelope.array();
    }

    public byte[] wrapUdp(byte[] payload) {
        return wrapUdp(payload, clock.instant().getEpochSecond(), UUID.randomUUID());
    }

    Verification verifyHttp(
            String method,
            String requestTarget,
            byte[] body,
            String timestampHeader,
            String nonce,
            String authorization) {
        if (!enabled()) {
            return new Verification(Result.DISABLED, body);
        }
        if (timestampHeader == null || nonce == null || authorization == null) {
            return new Verification(Result.MISSING_CREDENTIALS, null);
        }
        if (!validNonce(nonce) || !authorization.startsWith(SCHEME + " ")) {
            return new Verification(Result.MALFORMED_CREDENTIALS, null);
        }

        long timestamp;
        byte[] suppliedMac;
        try {
            timestamp = Long.parseLong(timestampHeader);
            suppliedMac = HEX.parseHex(authorization.substring(SCHEME.length() + 1));
        } catch (IllegalArgumentException exception) {
            return new Verification(Result.MALFORMED_CREDENTIALS, null);
        }
        if (suppliedMac.length != SHA_256_BYTES || !MessageDigest.isEqual(
                suppliedMac, hmac(httpCanonicalBytes(method, requestTarget, timestamp, nonce, body)))) {
            return new Verification(Result.INVALID_SIGNATURE, null);
        }
        return replayResult(replayProtector.claim("https", nonce, timestamp), body);
    }

    Verification verifyUdp(byte[] envelope) {
        Objects.requireNonNull(envelope, "UDP envelope must not be null");
        if (!enabled()) {
            return new Verification(Result.DISABLED, envelope);
        }
        if (envelope.length < UDP_OVERHEAD_BYTES) {
            return new Verification(Result.MISSING_CREDENTIALS, null);
        }

        ByteBuffer input = ByteBuffer.wrap(envelope);
        int magic = input.getInt();
        long timestamp = input.getLong();
        UUID nonce = new UUID(input.getLong(), input.getLong());
        int payloadLength = input.getInt();
        if (magic != UDP_MAGIC || payloadLength < 0
                || payloadLength != envelope.length - UDP_OVERHEAD_BYTES) {
            return new Verification(Result.MALFORMED_CREDENTIALS, null);
        }

        int authenticatedLength = UDP_FIXED_HEADER_BYTES + payloadLength;
        byte[] suppliedMac = Arrays.copyOfRange(envelope, authenticatedLength, envelope.length);
        byte[] authenticated = Arrays.copyOf(envelope, authenticatedLength);
        if (!MessageDigest.isEqual(suppliedMac, hmac(authenticated))) {
            return new Verification(Result.INVALID_SIGNATURE, null);
        }
        byte[] payload = Arrays.copyOfRange(envelope, UDP_FIXED_HEADER_BYTES, authenticatedLength);
        return replayResult(replayProtector.claim("udp", nonce.toString(), timestamp), payload);
    }

    private Verification replayResult(ReplayProtector.Result replayResult, byte[] payload) {
        Result result = switch (replayResult) {
            case ACCEPTED -> Result.AUTHENTICATED;
            case STALE_TIMESTAMP -> Result.STALE_TIMESTAMP;
            case REPLAYED -> Result.REPLAYED;
            case CAPACITY_EXCEEDED -> Result.REPLAY_CAPACITY_EXCEEDED;
        };
        return new Verification(result, result.accepted() ? payload : null);
    }

    private byte[] httpCanonicalBytes(
            String method, String requestTarget, long timestampSeconds, String nonce, byte[] body) {
        Objects.requireNonNull(method, "HTTP method must not be null");
        Objects.requireNonNull(requestTarget, "Request target must not be null");
        Objects.requireNonNull(nonce, "Nonce must not be null");
        Objects.requireNonNull(body, "HTTP body must not be null");
        String canonical = timestampSeconds + "\n" + nonce + "\n"
                + method.toUpperCase(Locale.ROOT) + "\n" + requestTarget + "\n"
                + HEX.formatHex(sha256(body));
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean validNonce(String nonce) {
        if (nonce.length() < 16 || nonce.length() > 128) {
            return false;
        }
        for (int index = 0; index < nonce.length(); index++) {
            char character = nonce.charAt(index);
            boolean allowed = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_' || character == '.' || character == '~';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static byte[] toUtf8(char[] characters) {
        return new String(characters).getBytes(StandardCharsets.UTF_8);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("HMAC authentication is disabled");
        }
    }

    record Verification(Result result, byte[] payload) {
        Verification {
            Objects.requireNonNull(result, "Authentication result must not be null");
            payload = payload == null ? null : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload == null ? null : payload.clone();
        }

        boolean accepted() {
            return result.accepted();
        }
    }

    enum Result {
        DISABLED("disabled", true),
        AUTHENTICATED("authenticated", true),
        MISSING_CREDENTIALS("missing_credentials", false),
        MALFORMED_CREDENTIALS("malformed_credentials", false),
        INVALID_SIGNATURE("invalid_signature", false),
        STALE_TIMESTAMP("stale_timestamp", false),
        REPLAYED("replayed", false),
        REPLAY_CAPACITY_EXCEEDED("replay_capacity_exceeded", false);

        private final String outcome;
        private final boolean accepted;

        Result(String outcome, boolean accepted) {
            this.outcome = outcome;
            this.accepted = accepted;
        }

        String outcome() {
            return outcome;
        }

        boolean accepted() {
            return accepted;
        }
    }
}
