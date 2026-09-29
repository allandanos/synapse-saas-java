package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.PostgresTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Auth rate limiting end to end (reference: {@code identity/rate_limit.py}):
 * the per-IP and per-identity buckets, the 429 problem document with
 * {@code Retry-After}, and X-Forwarded-For only from a trusted proxy.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRateLimitJourneyTest {

    static final int PER_IP = 6;
    static final int PER_IDENTITY = 2;

    /**
     * This test owns its whole property set — it registers the database itself
     * rather than inheriting {@code PostgresTestSupport}'s generous limits.
     */
    @DynamicPropertySource
    static void tightLimits(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatasource(registry);
        registry.add("synapse.auth-rate-limit-per-ip", () -> PER_IP);
        registry.add("synapse.auth-rate-limit-per-identity", () -> PER_IDENTITY);
        registry.add("synapse.auth-rate-window-seconds", () -> 60);
        registry.add("synapse.trusted-proxies", () -> "127.0.0.1/32");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private Res login(String email, Map<String, String> headers) throws Exception {
        return api.post("/v1/auth/login", headers, Map.of("email", email, "password", "not-the-password"));
    }

    @Test
    void theIdentityBucketTripsBeforeTheIpBucket() throws Exception {
        String email = "rl-id-" + ApiClient.uid() + "@conformance.example.com";
        // The peer is trusted, so a distinct forwarded client keeps the IP bucket fresh
        for (int attempt = 1; attempt <= PER_IDENTITY; attempt++) {
            Res res = login(email, Map.of("X-Forwarded-For", "203.0.113." + attempt));
            assertThat(res.status()).as("attempt " + attempt).isEqualTo(401);
        }
        Res limited = login(email, Map.of("X-Forwarded-For", "203.0.113.99"));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body().get("title").asText()).isEqualTo("rate limited");
        assertThat(limited.body().get("type").asText()).endsWith("/problems/rate_limited");
        assertThat(limited.body().get("limit").asInt()).isEqualTo(PER_IDENTITY);
        assertThat(limited.body().get("retry_after_seconds").asInt()).isBetween(1, 60);
        assertThat(limited.body().get("instance").asText()).isEqualTo("/v1/auth/login");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);

        // A different identity from the same forwarded client is unaffected
        Res other = login("rl-other-" + ApiClient.uid() + "@conformance.example.com", Map.of("X-Forwarded-For", "203.0.113.99"));
        assertThat(other.status()).isEqualTo(401);
    }

    @Test
    void theIpBucketTripsForOneClientAndSparesAnother() throws Exception {
        String client = "198.51.100.7";
        for (int attempt = 1; attempt <= PER_IP; attempt++) {
            // A distinct identity every time, so only the IP bucket can trip
            Res res = login("rl-ip-" + ApiClient.uid() + "@conformance.example.com", Map.of("X-Forwarded-For", client));
            assertThat(res.status()).as("attempt " + attempt).isEqualTo(401);
        }
        Res limited = login("rl-ip-" + ApiClient.uid() + "@conformance.example.com", Map.of("X-Forwarded-For", client));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body().get("limit").asInt()).isEqualTo(PER_IP);

        Res elsewhere = login("rl-ip-" + ApiClient.uid() + "@conformance.example.com", Map.of("X-Forwarded-For", "198.51.100.8"));
        assertThat(elsewhere.status()).isEqualTo(401);
    }

    @Test
    void aSpoofedForwardedPrefixCannotRotateTheBucket() throws Exception {
        // The proxy (127.0.0.1) appends the peer it saw; anything to its left is the client's claim
        String real = "198.51.100.42";
        for (int attempt = 1; attempt <= PER_IP; attempt++) {
            Res res = login("rl-spoof-" + ApiClient.uid() + "@conformance.example.com",
                Map.of("X-Forwarded-For", "10.10.10." + attempt + ", " + real));
            assertThat(res.status()).as("attempt " + attempt).isEqualTo(401);
        }
        Res limited = login("rl-spoof-" + ApiClient.uid() + "@conformance.example.com",
            Map.of("X-Forwarded-For", "10.10.10.250, " + real));
        assertThat(limited.status()).isEqualTo(429);
    }

    @Test
    void theBodyStillReachesTheHandlerAfterThePeek() throws Exception {
        // The identity peek reads the body; the handler must still parse it
        String email = "rl-body-" + ApiClient.uid() + "@conformance.example.com";
        Res registered = api.post("/v1/auth/register", Map.of("X-Forwarded-For", "203.0.113.200"),
            Map.of("email", email, "password", ApiClient.PASSWORD, "display_name", "Peeked"));
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.body().at("/user/email").asText()).isEqualTo(email);
    }

    @Test
    void unlistedRoutesAreNeverCounted() throws Exception {
        for (int attempt = 0; attempt < PER_IP * 3; attempt++) {
            assertThat(api.get("/v1/meta", Map.of("X-Forwarded-For", "203.0.113.250")).status()).isEqualTo(200);
        }
    }
}
