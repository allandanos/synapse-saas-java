package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.cache.Caches;
import dev.synapse.identity.ratelimit.RateLimiter;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.PostgresTestSupport;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The same caches against a real Redis ({@code SYNAPSE_TEST_REDIS_URL}, default
 * this port's {@code redis-java} on 6390). Skipped when none is reachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("redisAvailable")
class RedisCacheJourneyTest extends PostgresTestSupport {

    static final String REDIS_URL = System.getenv().getOrDefault("SYNAPSE_TEST_REDIS_URL", "redis://localhost:6390/0");

    static boolean redisAvailable() {
        URI uri = URI.create(REDIS_URL);
        try (Socket socket = new Socket(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort())) {
            return socket.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("synapse.redis-url", () -> REDIS_URL);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Caches caches;
    @Autowired CacheBackend backend;
    @Autowired RateLimiter limiter;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    @Test
    void readyzReportsTheRedisItIsActuallyTalkingTo() throws Exception {
        assertThat(backend.redis()).isTrue();
        Res ready = api.get("/readyz", Map.of());
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body().at("/checks/redis").asText()).isEqualTo("ok");
    }

    @Test
    void bodiesAndCountersRoundTripThroughRedis() {
        String key = "redis-journey-" + UUID.randomUUID();
        assertThat(caches.permissions().getVersioned(key).body()).isNull();
        caches.permissions().set(key, "org:read,org:update", 0);
        assertThat(backend.get("perm:v0:" + key)).isEqualTo("org:read,org:update");
        assertThat(caches.permissions().get(key)).isEqualTo("org:read,org:update");

        caches.permissions().bump(key);
        assertThat(backend.get("perm:ver:" + key)).isEqualTo("1");
        assertThat(caches.permissions().get(key)).isNull(); // the body under v0 is unreachable, never resurrected
    }

    @Test
    void theScopedTokenInvalidatesAcrossScopes() {
        String key = "flag-" + UUID.randomUUID();
        String scope = "org:" + UUID.randomUUID();
        var miss = caches.flags().getScoped(key, "all", scope);
        caches.flags().setScoped(key, "1", miss.token());
        assertThat(caches.flags().getScoped(key, "all", scope).body()).isEqualTo("1");
        caches.flags().bump(scope);
        assertThat(caches.flags().getScoped(key, "all", scope).body()).isNull();
    }

    @Test
    void theRateLimiterCountsInRedis() {
        String key = "journey:" + UUID.randomUUID();
        limiter.check(key, 5, 60);
        limiter.check(key, 5, 60);
        assertThat(backend.get(RateLimiter.PREFIX + ":" + key)).isEqualTo("2");
    }

    @Test
    void theOidcLoginStateSurvivesInRedisAndIsSingleUse() {
        String state = UUID.randomUUID().toString();
        caches.oidcState().set(state, "{\"verifier\":\"v\"}");
        assertThat(caches.oidcState().get(state)).isEqualTo("{\"verifier\":\"v\"}");
        caches.oidcState().delete(state);
        assertThat(caches.oidcState().get(state)).isNull();
    }
}
