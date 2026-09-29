package dev.synapse.core.cache;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis over Lettuce (reference: {@code core/redis.py}).
 *
 * <p>Reads and writes never propagate a failure: a Redis blip degrades the
 * cache to a miss, it never turns a request into a 500. Only {@link #ping()}
 * surfaces the error, so {@code /readyz} can report it.
 */
public final class RedisCacheBackend implements CacheBackend {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheBackend.class);
    public static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisCacheBackend(String url) {
        RedisURI uri = RedisURI.create(url);
        uri.setTimeout(TIMEOUT);
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
    }

    private RedisCommands<String, String> commands() {
        return connection.sync();
    }

    @Override
    public String get(String key) {
        try {
            return commands().get(key);
        } catch (RuntimeException e) {
            log.warn("cache_get_failed key={} error={}", key, e.toString());
            return null;
        }
    }

    @Override
    public void set(String key, String value, int ttlSeconds) {
        try {
            commands().setex(key, ttlSeconds, value);
        } catch (RuntimeException e) {
            log.warn("cache_set_failed key={} error={}", key, e.toString());
        }
    }

    @Override
    public long incr(String key, int ttlSeconds) {
        try {
            long value = commands().incr(key);
            commands().expire(key, ttlSeconds);
            return value;
        } catch (RuntimeException e) {
            log.warn("cache_bump_failed key={} error={}", key, e.toString());
            return -1;
        }
    }

    @Override
    public Hit hit(String key, int windowSeconds) {
        RedisCommands<String, String> commands = commands();
        long count = commands.incr(key);
        long ttl = commands.ttl(key);
        if (ttl == -1) {
            // INCR created the key without an expiry (first hit raced) — set it
            commands.expire(key, windowSeconds);
            ttl = windowSeconds;
        }
        return new Hit(count, Math.max(ttl, 1));
    }

    @Override
    public void ping() {
        commands().ping();
    }

    @Override
    public boolean redis() {
        return true;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
