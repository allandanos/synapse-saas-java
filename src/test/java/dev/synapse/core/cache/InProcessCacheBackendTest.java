package dev.synapse.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InProcessCacheBackendTest {

    @Test
    void expiredEntriesReadAsMisses() {
        InProcessCacheBackend backend = new InProcessCacheBackend();
        backend.set("k", "v", 0);
        assertThat(backend.get("k")).isNull();
    }

    @Test
    void incrCountsFromZero() {
        InProcessCacheBackend backend = new InProcessCacheBackend();
        assertThat(backend.incr("c", 60)).isEqualTo(1);
        assertThat(backend.incr("c", 60)).isEqualTo(2);
    }

    @Test
    void hitCountsInsideTheWindowAndReportsTheSecondsLeft() {
        InProcessCacheBackend backend = new InProcessCacheBackend();
        CacheBackend.Hit first = backend.hit("ip", 60);
        CacheBackend.Hit second = backend.hit("ip", 60);
        assertThat(first.count()).isEqualTo(1);
        assertThat(second.count()).isEqualTo(2);
        assertThat(second.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void reportsItselfAsNotRedis() {
        assertThat(new InProcessCacheBackend().redis()).isFalse();
    }
}
