package dev.synapse.core.cache;

import java.util.StringJoiner;

/**
 * Version-counter cache (reference: {@code core/cache.py:VersionedCache}).
 *
 * <p>Every cached body lives under {@code {ns}:v{version}:{key}}, where the
 * version comes from a separate counter {@code {ns}:ver:{key}}. Mutations bump
 * the counter; readers read the (tiny) counter first and only fetch a body for
 * a version they have not invalidated. No key scanning, no delete storms.
 *
 * <p>Three correctness rules, each of which closed a real bug in the reference:
 * <ul>
 *   <li>{@link #set(String, String, long)} writes under the version observed at
 *       <em>read</em> time ({@link #getVersioned}), never one re-read at write
 *       time: a bump in between must leave the new version empty, not fill it
 *       with the stale body.</li>
 *   <li>{@link #delete} is a bump. Resetting the counter to 0 would resurrect
 *       whatever body was cached under version 0.</li>
 *   <li>Invalidation belongs AFTER commit — see {@link DeferredBumps}.</li>
 * </ul>
 */
public final class VersionedCache {

    public static final int DEFAULT_TTL_SECONDS = 60;
    /** The counter outlives every body it versions. */
    public static final int VERSION_TTL_SECONDS = 3600;

    private final CacheBackend backend;
    private final String namespace;
    private final int ttlSeconds;

    public VersionedCache(CacheBackend backend, String namespace, int ttlSeconds) {
        this.backend = backend;
        this.namespace = namespace;
        this.ttlSeconds = ttlSeconds;
    }

    public String namespace() {
        return namespace;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }

    String versionKey(String key) {
        return namespace + ":ver:" + key;
    }

    String bodyKey(String key, long version) {
        return namespace + ":v" + version + ":" + key;
    }

    String scopedKey(String key, String token) {
        return namespace + ":s[" + token + "]:" + key;
    }

    public long currentVersion(String key) {
        String raw = backend.get(versionKey(key));
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0; // a corrupt counter behaves like "never bumped"
        }
    }

    /** (body, version) — hand the version back to {@link #set(String, String, long)} after a miss. */
    public Versioned getVersioned(String key) {
        long version = currentVersion(key);
        return new Versioned(backend.get(bodyKey(key, version)), version);
    }

    public String get(String key) {
        return getVersioned(key).body();
    }

    /** Store {@code value} under the version seen at read time. */
    public void set(String key, String value, long version) {
        backend.set(bodyKey(key, version), value, ttlSeconds);
    }

    /** Store under a freshly read version (only safe when nothing was read first). */
    public void set(String key, String value) {
        set(key, value, currentVersion(key));
    }

    /**
     * A body several counters can invalidate (a flag evaluation depends on the
     * global, org and user scopes). Hand the token back to {@link #setScoped}.
     */
    public Scoped getScoped(String key, String... scopes) {
        StringJoiner token = new StringJoiner(",");
        for (String scope : scopes) {
            token.add(scope + "=" + currentVersion(scope));
        }
        String joined = token.toString();
        return new Scoped(backend.get(scopedKey(key, joined)), joined);
    }

    public void setScoped(String key, String value, String token) {
        backend.set(scopedKey(key, token), value, ttlSeconds);
    }

    /** Invalidate: increment the version counter. The next read misses. */
    public long bump(String key) {
        return backend.incr(versionKey(key), VERSION_TTL_SECONDS);
    }

    /** Invalidate — implemented as a bump (see the class docs). */
    public void delete(String key) {
        bump(key);
    }

    public record Versioned(String body, long version) {}

    public record Scoped(String body, String token) {}
}
