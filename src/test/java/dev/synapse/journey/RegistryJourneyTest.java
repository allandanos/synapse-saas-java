package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import dev.synapse.worker.JobRegistry;

/**
 * The milestone-5 governance surfaces over a real Postgres: the agent registry
 * behind its entitlement, webhook endpoint management over the delivery engine,
 * feature flags (operator CRUD + overrides + rollout) and the audit read route.
 *
 * <p>The scheduler is off so the outbox only moves when a test asks it to.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.worker-enabled=false"
})
class RegistryJourneyTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JobRegistry jobs;
    @Autowired OutboxWriter outbox;
    @Autowired TransactionTemplate transaction;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        // The other journeys leave unpublished rows behind (they never run the worker);
        // retire them so a batch claim only ever sees this test's own events.
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL AND dead_at IS NULL").update();
    }

    /** Drain the outbox however many batches it takes. */
    private int dispatchAll() {
        int dispatched = 0;
        for (int batch = 0; batch < 10; batch++) {
            int count = jobs.run("dispatch_outbox");
            if (count <= 0) {
                break;
            }
            dispatched += count;
        }
        return dispatched;
    }

    // ── Agents ───────────────────────────────────────────────────────────────────

    @Test
    void theAgentRouterIsInvisibleWithoutTheEntitlementAndCrudOnceGranted() throws Exception {
        Tenant tenant = api.makeTenant("agents");
        JsonNode gate = assertProblem(api.get("/v1/agents", tenant.headers()), 403, "feature not entitled");
        assertThat(gate.get("feature").asText()).isEqualTo("agents");
        assertThat(gate.get("available_in").isArray()).isTrue();
        assertThat(gate.get("upgrade_url").isNull()).isFalse();

        grantFeature(tenant, "agents");
        assertThat(api.get("/v1/agents", tenant.headers()).status()).isEqualTo(200);

        String slug = "bot-" + ApiClient.uid();
        Res created = api.post("/v1/agents", tenant.headers(),
            Map.of("slug", slug, "name", "Bot", "config", Map.of("model", "x")));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("status")).isEqualTo("active");
        assertThat(created.body().at("/config/model").asText()).isEqualTo("x");
        String agentId = created.text("id");
        assertThat(outboxPayload(Events.AGENT_REGISTERED, UUID.fromString(agentId))).containsEntry("slug", slug);
        assertThat(auditEventTypes(tenant)).contains("agent.registered");

        // The slug is taken for this org even by a *deleted* agent — the unique index covers them.
        JsonNode conflict = assertProblem(api.post("/v1/agents", tenant.headers(),
            Map.of("slug", slug, "name", "Bot", "config", Map.of())), 409, "conflict");
        assertThat(conflict.get("slug").asText()).isEqualTo(slug);

        assertProblem(api.post("/v1/agents", tenant.headers(), Map.of("slug", "Bad Slug", "name", "Bot")), 422, "validation failed");
        assertProblem(api.post("/v1/agents", tenant.headers(), Map.of("slug", "ok-slug", "name", "x")), 422, "validation failed");

        Res patched = api.patch("/v1/agents/" + agentId, tenant.headers(), Map.of("name", "Bot 2"));
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.text("name")).isEqualTo("Bot 2");
        // config survives a name-only patch
        assertThat(patched.body().at("/config/model").asText()).isEqualTo("x");

        assertThat(api.post("/v1/agents/" + agentId + "/disable", tenant.headers(), null).text("status")).isEqualTo("disabled");
        assertThat(api.post("/v1/agents/" + agentId + "/enable", tenant.headers(), null).text("status")).isEqualTo("active");

        Res listed = api.get("/v1/agents", tenant.headers());
        assertThat(listed.header("X-Total-Count")).isEqualTo("1");
        assertThat(listed.body().get(0).get("id").asText()).isEqualTo(agentId);

        // Another org never sees it, by id or in its list
        Tenant other = api.makeTenant("agents-other");
        grantFeature(other, "agents");
        assertProblem(api.get("/v1/agents/" + agentId, other.headers()), 404, "not found");
        assertThat(api.get("/v1/agents", other.headers()).body().size()).isZero();

        assertThat(api.delete("/v1/agents/" + agentId, tenant.headers()).status()).isEqualTo(204);
        assertProblem(api.get("/v1/agents/" + agentId, tenant.headers()), 404, "not found");
        assertProblem(api.post("/v1/agents", tenant.headers(), Map.of("slug", slug, "name", "Bot")), 409, "conflict");
        assertThat(jdbc.sql("SELECT status FROM agents WHERE id = :id").param("id", UUID.fromString(agentId))
            .query(String.class).single()).isEqualTo("disabled");
    }

    // ── Webhook management ───────────────────────────────────────────────────────

    @Test
    void anEndpointShowsItsSecretOnceThenOnlyDeliveries() throws Exception {
        Tenant tenant = api.makeTenant("hooks");
        UUID orgId = UUID.fromString(tenant.orgId());

        assertProblem(api.post("/v1/webhooks/endpoints", tenant.headers(), Map.of("url", "not a url", "events", List.of())),
            422, "validation failed");

        String url = "https://hooks.example.com/" + ApiClient.uid();
        Res created = api.post("/v1/webhooks/endpoints", tenant.headers(),
            Map.of("url", url, "events", List.of(Events.MEMBER_INVITED), "description", "c"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("secret")).startsWith("whsec_");
        assertThat(created.body().get("is_active").asBoolean()).isTrue();
        assertThat(created.body().get("events").get(0).asText()).isEqualTo(Events.MEMBER_INVITED);
        String endpointId = created.text("id");
        // Stored encrypted, never in the clear
        assertThat(jdbc.sql("SELECT encode(secret_encrypted, 'escape') FROM webhook_endpoints WHERE id = :id")
            .param("id", UUID.fromString(endpointId)).query(String.class).single())
            .doesNotContain(created.text("secret"));

        Res listed = api.get("/v1/webhooks/endpoints", tenant.headers());
        assertThat(listed.header("X-Total-Count")).isEqualTo("1");
        assertThat(listed.body().get(0).has("secret")).as("the secret is never readable again").isFalse();
        assertThat(listed.body().get(0).get("id").asText()).isEqualTo(endpointId);

        // The catalogued event and its audit row, neither carrying the secret
        assertThat(outboxPayload(Events.WEBHOOK_ENDPOINT_CREATED, UUID.fromString(endpointId)))
            .containsEntry("endpoint_id", endpointId).containsEntry("url", url);
        Res auditCreated = api.get("/v1/audit?event_type=" + Events.WEBHOOK_ENDPOINT_CREATED, tenant.headers());
        assertThat(auditCreated.body().get("data").findValues("target_id").stream().map(JsonNode::asText))
            .containsExactly(endpointId);
        assertThat(auditCreated.body().get("data").get(0).get("target_type").asText()).isEqualTo("webhook_endpoint");
        assertThat(auditCreated.body().toString()).doesNotContain(created.text("secret"));

        // A second endpoint proves the list is ordered newest first
        String secondId = api.post("/v1/webhooks/endpoints", tenant.headers(),
            Map.of("url", url + "-2", "events", List.of())).text("id");
        assertThat(api.get("/v1/webhooks/endpoints", tenant.headers()).body().findValues("id").stream()
            .map(JsonNode::asText)).containsExactly(secondId, endpointId);
        assertThat(api.delete("/v1/webhooks/endpoints/" + secondId, tenant.headers()).status()).isEqualTo(204);

        assertThat(api.get("/v1/webhooks/deliveries?endpoint_id=" + endpointId, tenant.headers()).body().size()).isZero();

        // A real event through the outbox produces the delivery this endpoint subscribed to
        transaction.executeWithoutResult(status ->
            outbox.append(Events.MEMBER_INVITED, "membership", UUID.randomUUID(), orgId, Map.of("email", "x@example.com")));
        assertThat(dispatchAll()).isPositive();

        Res deliveries = api.get("/v1/webhooks/deliveries?endpoint_id=" + endpointId, tenant.headers());
        assertThat(deliveries.status()).isEqualTo(200);
        assertThat(deliveries.header("X-Total-Count")).isEqualTo("1");
        JsonNode delivery = deliveries.body().get(0);
        assertThat(delivery.get("event_type").asText()).isEqualTo(Events.MEMBER_INVITED);
        assertThat(delivery.get("status").asText()).isEqualTo("pending");
        assertThat(delivery.get("attempts").asInt()).isZero();
        assertThat(delivery.get("max_attempts").asInt()).isEqualTo(6);
        assertThat(delivery.get("endpoint_id").asText()).isEqualTo(endpointId);
        String deliveryId = delivery.get("id").asText();
        // The filter is optional and narrows: an unrelated endpoint id returns nothing
        assertThat(api.get("/v1/webhooks/deliveries", tenant.headers()).body().size()).isEqualTo(1);
        assertThat(api.get("/v1/webhooks/deliveries?endpoint_id=" + UUID.randomUUID(), tenant.headers()).body().size()).isZero();

        // Retry resets the ladder whatever state the row is in
        jdbc.sql("UPDATE webhook_deliveries SET status = 'exhausted', attempts = 6 WHERE id = :id")
            .param("id", UUID.fromString(deliveryId)).update();
        Res retried = api.post("/v1/webhooks/deliveries/" + deliveryId + "/retry", tenant.headers(), null);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.text("status")).isEqualTo("pending");
        assertThat(retried.body().get("attempts").asInt()).isZero();

        // An endpoint id is not a delivery id, and neither is another org's row
        assertProblem(api.post("/v1/webhooks/deliveries/" + endpointId + "/retry", tenant.headers(), null),
            404, "webhook delivery not found");
        Tenant other = api.makeTenant("hooks-other");
        assertThat(api.get("/v1/webhooks/endpoints", other.headers()).body().size()).isZero();
        assertProblem(api.delete("/v1/webhooks/endpoints/" + endpointId, other.headers()), 404, "webhook endpoint not found");
        assertProblem(api.post("/v1/webhooks/deliveries/" + deliveryId + "/retry", other.headers(), null),
            404, "webhook delivery not found");

        assertThat(api.delete("/v1/webhooks/endpoints/" + endpointId, tenant.headers()).status()).isEqualTo(204);
        assertProblem(api.delete("/v1/webhooks/endpoints/" + endpointId, tenant.headers()), 404, "webhook endpoint not found");
        assertThat(api.get("/v1/audit?event_type=" + Events.WEBHOOK_ENDPOINT_DELETED, tenant.headers())
            .body().get("data").findValues("target_id").stream().map(JsonNode::asText))
            .containsExactly(endpointId, secondId);
        assertThat(api.get("/v1/webhooks/deliveries", tenant.headers()).body().size())
            .as("deliveries cascade with their endpoint").isZero();
    }

    // ── Feature flags ────────────────────────────────────────────────────────────

    @Test
    void flagsAreOperatorOwnedAndResolveUserThenOrgThenGlobal() throws Exception {
        Tenant tenant = api.makeTenant("flags");
        Map<String, String> platform = platform();
        String key = "flag-" + ApiClient.uid();

        // Unknown flags are off, and the management surface is invisible to tenants
        assertThat(api.get("/v1/feature-flags/check/nope-" + ApiClient.uid(), tenant.headers())
            .body().get("enabled").asBoolean()).isFalse();
        assertProblem(api.post("/v1/feature-flags", tenant.headers(), Map.of("key", key, "name", key)), 404, "not found");
        assertProblem(api.get("/v1/feature-flags", tenant.headers()), 404, "not found");

        Res created = api.post("/v1/feature-flags", platform, Map.of("key", key, "name", key, "enabled", false));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("rollout_percentage").isNull()).isTrue();
        JsonNode conflict = assertProblem(api.post("/v1/feature-flags", platform, Map.of("key", key, "name", key)), 409, "conflict");
        assertThat(conflict.get("key").asText()).isEqualTo(key);
        assertProblem(api.post("/v1/feature-flags", platform, Map.of("key", "Bad Key", "name", "x y")), 422, "validation failed");
        assertProblem(api.patch("/v1/feature-flags/nope-" + ApiClient.uid(), platform, Map.of("enabled", true)),
            404, "feature flag not found");

        Res listed = api.get("/v1/feature-flags?limit=100", platform);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body().findValues("key").stream().map(JsonNode::asText)).contains(key);

        assertThat(checkFlag(tenant, key)).isFalse();

        // Org override beats the global default…
        Res override = api.post("/v1/feature-flags/" + key + "/overrides", platform,
            Map.of("organization_id", tenant.orgId(), "enabled", true, "note", "journey"));
        assertThat(override.status()).isEqualTo(201);
        assertThat(override.body().get("user_id").isNull()).isTrue();
        assertThat(checkFlag(tenant, key)).isTrue();

        // …and a user override beats the org one.
        assertThat(api.post("/v1/feature-flags/" + key + "/overrides", platform,
            Map.of("user_id", tenant.userId(), "enabled", false)).status()).isEqualTo(201);
        assertThat(checkFlag(tenant, key)).isFalse();

        Res overrides = api.get("/v1/feature-flags/" + key + "/overrides", platform);
        assertThat(overrides.header("X-Total-Count")).isEqualTo("2");
        // Re-posting the same scope edits in place rather than piling rows up
        assertThat(api.post("/v1/feature-flags/" + key + "/overrides", platform,
            Map.of("user_id", tenant.userId(), "enabled", true)).text("id"))
            .isEqualTo(overrides.body().findValues("id").get(0).asText());
        assertThat(checkFlag(tenant, key)).isTrue();

        // Exactly one scope: neither is unscoped, both is ambiguous
        assertProblem(api.post("/v1/feature-flags/" + key + "/overrides", platform, Map.of("enabled", true)),
            422, "validation failed");
        assertProblem(api.post("/v1/feature-flags/" + key + "/overrides", platform,
            Map.of("organization_id", tenant.orgId(), "user_id", tenant.userId(), "enabled", true)),
            422, "validation failed");
        assertProblem(api.post("/v1/feature-flags/nope/overrides", platform,
            Map.of("organization_id", tenant.orgId(), "enabled", true)), 404, "feature flag not found");

        for (JsonNode row : overrides.body()) {
            assertThat(api.delete("/v1/feature-flags/overrides/" + row.get("id").asText(), platform).status()).isEqualTo(204);
        }
        assertProblem(api.delete("/v1/feature-flags/overrides/" + UUID.randomUUID(), platform), 404, "feature flag not found");
        assertThat(checkFlag(tenant, key)).isFalse();

        Res updated = api.patch("/v1/feature-flags/" + key, platform, Map.of("enabled", true));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("enabled").asBoolean()).isTrue();
        assertThat(checkFlag(tenant, key)).isTrue();

        // A rollout replaces the boolean default and is stable for a given user
        assertThat(api.patch("/v1/feature-flags/" + key, platform, Map.of("rollout_percentage", 0)).status()).isEqualTo(200);
        assertThat(checkFlag(tenant, key)).isFalse();
        assertThat(api.patch("/v1/feature-flags/" + key, platform, Map.of("rollout_percentage", 100)).status()).isEqualTo(200);
        assertThat(checkFlag(tenant, key)).isTrue();
        assertThat(checkFlag(tenant, key)).isTrue();
        assertProblem(api.patch("/v1/feature-flags/" + key, platform, Map.of("rollout_percentage", 101)), 422, "validation failed");
    }

    // ── Audit ────────────────────────────────────────────────────────────────────

    @Test
    void theAuditRouteIsOrgScopedFilterableAndAttributesApiKeys() throws Exception {
        Tenant tenant = api.makeTenant("audit");
        Res key = api.post("/v1/api-keys", tenant.headers(), Map.of("name", "k-" + ApiClient.uid()));
        assertThat(key.status()).isEqualTo(201);

        Res page = api.get("/v1/audit?limit=5", tenant.headers());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().has("data") && page.body().has("next_cursor")).isTrue();
        assertThat(page.body().get("next_cursor").isNull()).isTrue();
        assertThat(page.body().get("data").size()).isBetween(1, 5);
        JsonNode row = page.body().get("data").get(0);
        assertThat(row.get("event_type").asText()).isEqualTo("api_key.created");
        assertThat(row.get("actor_user_id").asText()).isEqualTo(tenant.userId());
        assertThat(row.get("actor_type").asText()).isEqualTo("user");
        assertThat(row.get("organization_id").asText()).isEqualTo(tenant.orgId());
        assertThat(row.get("request_id").asText()).isNotBlank();
        assertThat(row.at("/diff/name").isMissingNode()).isFalse();

        // Newest first
        List<String> types = page.body().get("data").findValues("event_type").stream().map(JsonNode::asText).toList();
        assertThat(types.get(0)).isEqualTo("api_key.created");

        Res filtered = api.get("/v1/audit?event_type=api_key.created", tenant.headers());
        assertThat(filtered.body().get("data").findValues("event_type").stream().map(JsonNode::asText))
            .allMatch("api_key.created"::equals);
        assertThat(api.get("/v1/audit?event_type=nope.nothing", tenant.headers()).body().get("data").size()).isZero();
        assertThat(api.get("/v1/audit?actor_user_id=" + tenant.userId(), tenant.headers()).body().get("data").size())
            .isGreaterThanOrEqualTo(1);
        assertThat(api.get("/v1/audit?actor_user_id=" + UUID.randomUUID(), tenant.headers()).body().get("data").size()).isZero();

        assertProblem(api.get("/v1/audit?limit=0", tenant.headers()), 422, "validation failed");
        assertProblem(api.get("/v1/audit?limit=101", tenant.headers()), 422, "validation failed");
        assertProblem(api.get("/v1/audit?offset=-1", tenant.headers()), 422, "validation failed");
        assertProblem(api.get("/v1/audit?actor_user_id=nope", tenant.headers()), 422, "validation failed");

        // A key's actions are attributed to the human who created it, with the key id in the diff
        Map<String, String> asKey = Map.of("Authorization", "Bearer " + key.text("key"), "X-Org-Id", tenant.orgId());
        assertThat(api.post("/v1/api-keys", asKey, Map.of("name", "child-" + ApiClient.uid())).status()).isEqualTo(201);
        JsonNode byKey = api.get("/v1/audit?limit=1", tenant.headers()).body().get("data").get(0);
        assertThat(byKey.get("actor_type").asText()).isEqualTo("api_key");
        assertThat(byKey.get("actor_user_id").asText()).isEqualTo(tenant.userId());
        assertThat(byKey.at("/diff/api_key_id").asText()).isEqualTo(key.text("id"));

        // Another org's trail is not readable from here: its page only ever carries its own rows
        Tenant other = api.makeTenant("audit-other");
        assertThat(api.get("/v1/audit", other.headers()).body().get("data").findValues("organization_id").stream()
            .map(JsonNode::asText)).allMatch(other.orgId()::equals).doesNotContain(tenant.orgId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private boolean checkFlag(Tenant tenant, String key) throws Exception {
        Res res = api.get("/v1/feature-flags/check/" + key, tenant.headers());
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.text("key")).isEqualTo(key);
        return res.body().get("enabled").asBoolean();
    }

    private List<String> auditEventTypes(Tenant tenant) throws Exception {
        return api.get("/v1/audit", tenant.headers()).body().get("data").findValues("event_type").stream()
            .map(JsonNode::asText).toList();
    }

    private void grantFeature(Tenant tenant, String feature) throws Exception {
        Res res = api.post("/v1/admin/orgs/" + tenant.orgId() + "/entitlements/grants", platform(),
            Map.of("feature_key", feature, "source", "beta"));
        assertThat(res.status()).isEqualTo(201);
    }

    private Map<String, String> platform() throws Exception {
        Res login = api.post("/v1/auth/login", Map.of(),
            Map.of("email", ApiJourneyTest.ADMIN_EMAIL, "password", ApiJourneyTest.ADMIN_PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        return Map.of("Authorization", "Bearer " + login.body().at("/tokens/access_token").asText());
    }

    private Map<String, Object> outboxPayload(String eventType, UUID aggregateId) throws Exception {
        String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE event_type = :type AND aggregate_id = :id")
            .param("type", eventType).param("id", aggregateId).query(String.class).single();
        return new HashMap<>(json.readValue(payload, Map.class));
    }
}
