package dev.synapse.core.cache;

/**
 * Redis was configured but the client could not be built (bad URL, DNS, refused
 * connection at boot). Every read is a miss, every write is dropped and
 * {@code /readyz} reports the original error — the framework keeps serving.
 */
final class UnavailableRedisBackend implements CacheBackend {

    private final String url;
    private final RuntimeException cause;

    UnavailableRedisBackend(String url, RuntimeException cause) {
        this.url = url;
        this.cause = cause;
    }

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
        throw new IllegalStateException("Redis at " + url + " is unavailable: " + cause.getMessage(), cause);
    }

    @Override
    public void ping() {
        throw new IllegalStateException("Redis at " + url + " is unavailable: " + cause.getMessage(), cause);
    }

    @Override
    public boolean redis() {
        return true;
    }

    @Override
    public void close() {
        // nothing was opened
    }
}
