package dev.synapse.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres for {@code @SpringBootTest}s: {@code SYNAPSE_TEST_JDBC_URL}
 * (+ {@code SYNAPSE_TEST_DB_USER}/{@code SYNAPSE_TEST_DB_PASSWORD}, default
 * synapse/synapse) points at an existing scratch database; otherwise a
 * Testcontainers {@code pgvector/pgvector:pg17} (the baseline needs the
 * {@code vector} and {@code citext} extensions) is started once per JVM.
 * Flyway applies {@code V1__baseline.sql} on boot; every test creates its own
 * users/orgs with random suffixes so no truncation is needed.
 */
public abstract class PostgresTestSupport {

    private static final String EXTERNAL_URL = System.getenv("SYNAPSE_TEST_JDBC_URL");
    private static PostgreSQLContainer<?> container;

    /**
     * The journeys register dozens of users from one address; the per-IP and
     * per-identity auth limits would trip long before the assertions do. The
     * test that exercises the limiter registers the database itself (through
     * {@link #registerDatasource}) instead of extending this class, so its own
     * low limits are the only ones in play.
     */
    @DynamicPropertySource
    static void authRateLimits(DynamicPropertyRegistry registry) {
        registry.add("synapse.auth-rate-limit-per-ip", () -> 100_000);
        registry.add("synapse.auth-rate-limit-per-identity", () -> 100_000);
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registerDatasource(registry);
    }

    /** {@code SYNAPSE_TEST_JDBC_URL} or a Testcontainers Postgres, shared per JVM. */
    public static void registerDatasource(DynamicPropertyRegistry registry) {
        // Spring caches one context per distinct property set and the suite has a dozen;
        // a lean, quickly-drained pool keeps them all inside one Postgres' max_connections
        // (the scratch server is shared with the other ports).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("spring.datasource.hikari.idle-timeout", () -> 10_000);
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> env("SYNAPSE_TEST_DB_USER", "synapse"));
            registry.add("spring.datasource.password", () -> env("SYNAPSE_TEST_DB_PASSWORD", "synapse"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("synapse_java_test").withUsername("synapse").withPassword("synapse");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
