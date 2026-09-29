package dev.synapse.identity.ratelimit;

import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.errors.RateLimitedError;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter (reference: {@code core/rate_limit.py:RateLimiter}).
 *
 * <p>Redis {@code INCR} + {@code EXPIRE} when one is configured, a per-process
 * window map otherwise — acceptable degradation for a single dev instance;
 * production runs Redis.
 */
@Component
public class RateLimiter {

    public static final String PREFIX = "rl";

    private final CacheBackend backend;

    public RateLimiter(CacheBackend backend) {
        this.backend = backend;
    }

    /**
     * Raise {@link RateLimitedError} when {@code key} exceeds {@code limit} in
     * the window. Backend failures propagate: the caller decides (the auth
     * filter fails open).
     */
    public void check(String key, int limit, int windowSeconds) {
        CacheBackend.Hit hit = backend.hit(PREFIX + ":" + key, windowSeconds);
        if (hit.count() > limit) {
            throw new RateLimitedError(hit.retryAfterSeconds(), limit);
        }
    }
}
