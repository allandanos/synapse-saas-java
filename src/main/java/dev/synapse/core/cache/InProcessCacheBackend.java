package dev.synapse.core.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-process TTL map (reference: {@code core/cache.py:TTLDictBackend}).
 *
 * <p>The fallback when no {@code SYNAPSE_REDIS_URL} is configured: correct for
 * one instance, invisible to any other. Production runs Redis.
 */
public final class InProcessCacheBackend implements CacheBackend {

    private record Entry(long expiresAtNanos, String value) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, long[]> windows = new ConcurrentHashMap<>(); // key -> {window, count}

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtNanos() < System.nanoTime()) {
            store.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    public void set(String key, String value, int ttlSeconds) {
        store.put(key, new Entry(System.nanoTime() + ttlSeconds * 1_000_000_000L, value));
    }

    @Override
    public long incr(String key, int ttlSeconds) {
        // compute() so two threads incrementing the same counter cannot lose an increment
        Entry updated = store.compute(key, (k, existing) -> {
            long current = existing == null || existing.expiresAtNanos() < System.nanoTime() ? 0 : Long.parseLong(existing.value());
            return new Entry(System.nanoTime() + ttlSeconds * 1_000_000_000L, String.valueOf(current + 1));
        });
        return Long.parseLong(updated.value());
    }

    @Override
    public Hit hit(String key, int windowSeconds) {
        long now = System.currentTimeMillis() / 1000L;
        long window = now / windowSeconds;
        long[] counter = windows.compute(key, (k, existing) ->
            existing == null || existing[0] != window ? new long[] {window, 1} : new long[] {window, existing[1] + 1});
        long retryAfter = windowSeconds - (now % windowSeconds);
        return new Hit(counter[1], Math.max(retryAfter, 1));
    }

    @Override
    public void ping() {
        // always available
    }

    @Override
    public boolean redis() {
        return false;
    }

    /** Test hook: forget everything. */
    public void clear() {
        store.clear();
        windows.clear();
    }
}
