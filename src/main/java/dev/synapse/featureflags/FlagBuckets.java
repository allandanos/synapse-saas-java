package dev.synapse.featureflags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic percentage rollouts (reference: {@code feature_flags/service.py}).
 *
 * <p>The bucket is the first four bytes of {@code sha256("<flag>:<identifier>")}
 * read big-endian, modulo {@link #BUCKETS}. Nothing random is consulted at read
 * time, so the same (flag, identifier) always resolves the same way — within a
 * request, across requests, and across processes.
 */
public final class FlagBuckets {

    public static final int BUCKETS = 10_000;

    private FlagBuckets() {}

    /** Bucket {@code 0..BUCKETS-1} for {@code (flagKey, identifier)}. */
    public static int bucketOf(String flagKey, String identifier) {
        byte[] digest = sha256(flagKey + ":" + identifier);
        // Python: int.from_bytes(digest[:4], "big") — unsigned, hence the long mask.
        long value = ((long) (digest[0] & 0xFF) << 24)
            | ((long) (digest[1] & 0xFF) << 16)
            | ((long) (digest[2] & 0xFF) << 8)
            | (digest[3] & 0xFF);
        return (int) (value % BUCKETS);
    }

    /** True when {@code identifier} falls inside the first {@code percentage}% of buckets. */
    public static boolean inRollout(String flagKey, String identifier, int percentage) {
        return bucketOf(flagKey, identifier) < (long) BUCKETS * percentage / 100;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
