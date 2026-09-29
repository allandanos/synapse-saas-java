package dev.synapse.core.cache;

import dev.synapse.core.config.SynapseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One cache backend for the whole process: Redis when {@code SYNAPSE_REDIS_URL}
 * is set, the per-process TTL map otherwise.
 *
 * <p>Unlike the reference (whose default points at its own dev stack on 6380),
 * the port ships with no Redis URL: caches, the auth rate limiter and the OIDC
 * login state stay in-process until one is configured, and {@code /readyz}
 * reports {@code redis: not_configured}.
 */
@Configuration
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean(destroyMethod = "close")
    CacheBackend cacheBackend(SynapseProperties props) {
        String url = props.redisUrl();
        if (url == null || url.isBlank()) {
            log.info("cache_backend backend=in_process reason=no_redis_url");
            return new InProcessCacheBackend();
        }
        try {
            RedisCacheBackend backend = new RedisCacheBackend(url);
            log.info("cache_backend backend=redis url={}", url);
            return backend;
        } catch (RuntimeException e) {
            // A bad URL must not stop the server: /readyz reports the error instead.
            log.error("cache_backend_unavailable url={} error={}", url, e.toString());
            return new UnavailableRedisBackend(url, e);
        }
    }
}
