package dev.synapse.core.cache;

/**
 * The key/value operations {@link VersionedCache} and the auth rate limiter
 * need (reference: {@code core/cache.py:CacheBackend}).
 *
 * <p>Two implementations: {@link RedisCacheBackend} when {@code SYNAPSE_REDIS_URL}
 * is set (shared across every instance) and {@link InProcessCacheBackend}
 * otherwise (single process, good enough to run lean — the reference's TTL dict).
 */
public interface CacheBackend {

    /** The stored body, or {@code null} on a miss. Never throws: an error IS a miss. */
    String get(String key);

    /** Store with an expiry. Never throws. */
    void set(String key, String value, int ttlSeconds);

    /** Increment a counter and (re)arm its expiry. Never throws; -1 signals a failure. */
    long incr(String key, int ttlSeconds);

    /** Fixed-window counter for rate limiting: the post-increment count and the seconds left. */
    Hit hit(String key, int windowSeconds);

    /** Probe for {@code /readyz}; throws when the backend is unreachable. */
    void ping();

    /** True when this is a real Redis (drives {@code checks.redis} in {@code /readyz}). */
    boolean redis();

    /** Release the connection at shutdown; nothing to do for the in-process backend. */
    default void close() {
        // no resources by default
    }

    record Hit(long count, long retryAfterSeconds) {}
}
