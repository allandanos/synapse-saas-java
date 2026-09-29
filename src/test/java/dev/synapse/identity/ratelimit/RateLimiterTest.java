package dev.synapse.identity.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.cache.InProcessCacheBackend;
import dev.synapse.core.errors.RateLimitedError;
import org.junit.jupiter.api.Test;

/** Fixed-window counting and how a backend outage surfaces (the filter fails open on it). */
class RateLimiterTest {

    @Test
    void allowsUpToTheLimitThenRaises() {
        RateLimiter limiter = new RateLimiter(new InProcessCacheBackend());
        for (int i = 0; i < 3; i++) {
            limiter.check("auth:ip:1.2.3.4", 3, 60);
        }
        RateLimitedError error = catchThrowableOfType(
            () -> limiter.check("auth:ip:1.2.3.4", 3, 60), RateLimitedError.class);
        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(429);
        assertThat(error.title()).isEqualTo("rate_limited");
        assertThat(error.extras()).containsEntry("limit", 3);
        assertThat(error.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void bucketsAreIndependent() {
        RateLimiter limiter = new RateLimiter(new InProcessCacheBackend());
        limiter.check("auth:ip:1.1.1.1", 1, 60);
        limiter.check("auth:id:a@example.com", 1, 60); // a different key has its own window
        assertThatThrownBy(() -> limiter.check("auth:ip:1.1.1.1", 1, 60)).isInstanceOf(RateLimitedError.class);
    }

    @Test
    void aBackendOutageSurfacesAsAPlainRuntimeFailure() {
        RateLimiter limiter = new RateLimiter(new BrokenBackend());
        assertThatThrownBy(() -> limiter.check("auth:ip:1.1.1.1", 5, 60))
            .isInstanceOf(IllegalStateException.class)     // NOT a RateLimitedError: the filter lets it through
            .isNotInstanceOf(RateLimitedError.class);
    }

    private static final class BrokenBackend implements CacheBackend {
        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public void set(String key, String value, int ttlSeconds) {
            // dropped
        }

        @Override
        public long incr(String key, int ttlSeconds) {
            return -1;
        }

        @Override
        public Hit hit(String key, int windowSeconds) {
            throw new IllegalStateException("redis is down");
        }

        @Override
        public void ping() {
            throw new IllegalStateException("redis is down");
        }

        @Override
        public boolean redis() {
            return true;
        }
    }
}
