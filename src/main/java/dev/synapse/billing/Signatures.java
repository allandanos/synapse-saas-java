package dev.synapse.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signature primitives (reference: {@code core/security.py}).
 *
 * <p>{@link #signPayload} is the Stripe-style v1 scheme — hex
 * HMAC-SHA256 over {@code "{timestamp}." + body} — shared by Stripe, PayMongo
 * and our own outbound deliveries. Paddle signs {@code "{ts}:" + body}
 * (colon). Comparison is always constant time.
 */
public final class Signatures {

    public static final String HMAC_SHA256 = "HmacSHA256";
    /** Both Stripe and PayMongo reject anything older than five minutes. */
    public static final long WEBHOOK_TOLERANCE_SECONDS = 300;

    private Signatures() {}

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Stripe-style v1 signature: {@code HMAC_SHA256(secret, "{timestamp}." + payload)} as lowercase hex. */
    public static String signPayload(byte[] payload, String secret, long timestamp) {
        return hmacHex(secret, (timestamp + ".").getBytes(StandardCharsets.UTF_8), payload);
    }

    public static boolean verifySignature(byte[] payload, String secret, long timestamp, String signature) {
        return constantTimeEquals(signPayload(payload, secret, timestamp), signature);
    }

    /** Paddle signs {@code "{ts}:" + body} rather than Stripe's dot. */
    public static String signPaddle(byte[] payload, String secret, long timestamp) {
        return hmacHex(secret, (timestamp + ":").getBytes(StandardCharsets.UTF_8), payload);
    }

    public static String hmacHex(String secret, byte[] prefix, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            mac.update(prefix);
            mac.update(payload);
            return hex(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** True when {@code timestamp} is inside the provider's replay window. */
    public static boolean withinTolerance(long timestamp, long nowSeconds) {
        return Math.abs(nowSeconds - timestamp) <= WEBHOOK_TOLERANCE_SECONDS;
    }
}
