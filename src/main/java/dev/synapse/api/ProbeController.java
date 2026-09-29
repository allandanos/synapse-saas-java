package dev.synapse.api;

import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.config.SynapseProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Milestone 1 slice of the contract: liveness, readiness, discovery. */
@RestController
public class ProbeController {

    /** A dependency the deployment deliberately does not have is not a failure. */
    public static final String NOT_CONFIGURED = "not_configured";

    private final JdbcClient jdbc;
    private final SynapseProperties props;
    private final CacheBackend cache;

    public ProbeController(JdbcClient jdbc, SynapseProperties props, CacheBackend cache) {
        this.jdbc = jdbc;
        this.props = props;
        this.cache = cache;
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    /** 200 only when every dependency answers; 503 otherwise (same body shape as the reference). */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        Map<String, String> checks = new LinkedHashMap<>();
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            checks.put("database", "ok");
        } catch (RuntimeException e) {
            checks.put("database", "error: " + e.getMessage());
        }
        if (!cache.redis()) {
            checks.put("redis", NOT_CONFIGURED); // SYNAPSE_REDIS_URL unset: caches are per-process
        } else {
            try {
                cache.ping();
                checks.put("redis", "ok");
            } catch (RuntimeException e) {
                checks.put("redis", "error: " + e.getMessage());
            }
        }
        boolean ok = checks.values().stream().allMatch(value -> "ok".equals(value) || NOT_CONFIGURED.equals(value));
        Map<String, Object> body = Map.of("status", ok ? "ok" : "error", "checks", checks);
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }

    @GetMapping("/v1/meta")
    public Map<String, String> meta() {
        return Map.of(
            "framework", "synapse-saas",
            "version", props.version(),
            "billing_provider", props.billingProvider(),
            "identity_provider", props.identityProvider(),
            "tenant_isolation", props.tenantIsolation());
    }
}
