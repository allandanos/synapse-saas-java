package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.cache.Caches;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cached reads stay correct across writes: a permission set, an
 * entitlement resolution and a flag evaluation are all memoised, and every
 * mutation that changes the answer invalidates them for the very next request
 * (reference: {@code core/cache.py} plus the bump sites in the services).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD
})
class CacheJourneyTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Caches caches;
    @Autowired CacheBackend backend;

    ApiClient api;
    Map<String, String> operator;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        Res login = api.post("/v1/auth/login", Map.of(),
            Map.of("email", ApiJourneyTest.ADMIN_EMAIL, "password", ApiJourneyTest.ADMIN_PASSWORD));
        operator = Map.of("Authorization", "Bearer " + login.body().at("/tokens/access_token").asText());
    }

    @Test
    void withoutARedisUrlTheBackendIsInProcessAndReadyzSaysSo() throws Exception {
        assertThat(backend.redis()).isFalse();
        Res ready = api.get("/readyz", Map.of());
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body().at("/checks/redis").asText()).isEqualTo("not_configured");
        assertThat(ready.body().at("/checks/database").asText()).isEqualTo("ok");
        assertThat(ready.body().get("status").asText()).isEqualTo("ok");
    }

    @Test
    void aRoleChangeIsVisibleToTheVeryNextRequest() throws Exception {
        Tenant owner = api.makeTenant("cache-role");
        String email = "cache-member-" + ApiClient.uid() + "@conformance.example.com";
        Res invite = api.post("/v1/orgs/current/members/invite", owner.headers(),
            Map.of("email", email, "role_keys", List.of("member")));
        assertThat(invite.status()).isEqualTo(201);
        Res registered = api.register(email, "Member");
        String memberToken = registered.body().at("/tokens/access_token").asText();
        String inviteToken = inviteToken(email);
        api.post("/v1/auth/accept-invite", Map.of("Authorization", "Bearer " + memberToken), Map.of("token", inviteToken));

        Map<String, String> memberHeaders = Map.of("Authorization", "Bearer " + memberToken, "X-Org-Id", owner.orgId());
        // `member` cannot list the org's members; the answer is now cached
        assertThat(api.get("/v1/orgs/current/members", memberHeaders).status()).isEqualTo(403);

        assertThat(api.patch("/v1/memberships/" + invite.text("id"), owner.headers(),
            Map.of("role_keys", List.of("admin"))).status()).isEqualTo(200);

        // No TTL wait: the mutation bumped the version inside the request AND after commit
        assertThat(api.get("/v1/orgs/current/members", memberHeaders).status()).isEqualTo(200);

        // …and back again
        assertThat(api.patch("/v1/memberships/" + invite.text("id"), owner.headers(),
            Map.of("role_keys", List.of("member"))).status()).isEqualTo(200);
        assertThat(api.get("/v1/orgs/current/members", memberHeaders).status()).isEqualTo(403);
    }

    @Test
    void anOperatorGrantIsVisibleToTheVeryNextEntitlementsRead() throws Exception {
        Tenant owner = api.makeTenant("cache-entl");
        Res before = api.get("/v1/entitlements", owner.headers());
        assertThat(before.status()).isEqualTo(200);
        assertThat(feature(before, "advanced_reports")).isFalse();

        Res granted = api.post("/v1/admin/orgs/" + owner.orgId() + "/entitlements/grants", operator,
            Map.of("feature_key", "advanced_reports", "source", "override", "enabled", true));
        assertThat(granted.status()).isEqualTo(201);

        Res after = api.get("/v1/entitlements", owner.headers());
        assertThat(feature(after, "advanced_reports")).isTrue();

        assertThat(api.delete("/v1/admin/orgs/" + owner.orgId() + "/entitlements/grants/" + granted.text("id"), operator)
            .status()).isEqualTo(204);
        assertThat(feature(api.get("/v1/entitlements", owner.headers()), "advanced_reports")).isFalse();
    }

    @Test
    void aFlagOverrideIsVisibleToTheVeryNextCheck() throws Exception {
        Tenant owner = api.makeTenant("cache-flag");
        String key = "cache_flag_" + ApiClient.uid();
        assertThat(api.post("/v1/feature-flags", operator,
            Map.of("key", key, "name", "Cache flag", "enabled", false)).status()).isEqualTo(201);

        assertThat(api.get("/v1/feature-flags/check/" + key, owner.headers()).body().get("enabled").asBoolean()).isFalse();

        Res override = api.post("/v1/feature-flags/" + key + "/overrides", operator,
            Map.of("organization_id", owner.orgId(), "enabled", true));
        assertThat(override.status()).isEqualTo(201);
        assertThat(api.get("/v1/feature-flags/check/" + key, owner.headers()).body().get("enabled").asBoolean()).isTrue();

        // A global flag edit invalidates every evaluation through the "all" scope
        assertThat(api.delete("/v1/feature-flags/overrides/" + override.text("id"), operator).status()).isEqualTo(204);
        assertThat(api.get("/v1/feature-flags/check/" + key, owner.headers()).body().get("enabled").asBoolean()).isFalse();
        assertThat(api.patch("/v1/feature-flags/" + key, operator, Map.of("enabled", true)).status()).isEqualTo(200);
        assertThat(api.get("/v1/feature-flags/check/" + key, owner.headers()).body().get("enabled").asBoolean()).isTrue();
    }

    @Test
    void theCachedBodiesLiveUnderTheirNamespaceAndVersion() {
        // The wire layout the reference pins: {ns}:ver:{key} and {ns}:v{version}:{key}
        caches.permissions().set("u:o", "org:read", 0);
        assertThat(backend.get("perm:v0:u:o")).isEqualTo("org:read");
        caches.permissions().bump("u:o");
        assertThat(backend.get("perm:ver:u:o")).isEqualTo("1");
        assertThat(caches.permissions().get("u:o")).isNull();
        assertThat(caches.entitlements().ttlSeconds()).isEqualTo(60);
        assertThat(caches.permissions().ttlSeconds()).isEqualTo(30);
        assertThat(caches.fga().ttlSeconds()).isEqualTo(30);
        assertThat(caches.flags().ttlSeconds()).isEqualTo(30);
        assertThat(caches.oidcState().ttlSeconds()).isEqualTo(600);
    }

    private static boolean feature(Res response, String feature) {
        return response.body().get("features").toString().contains("\"" + feature + "\"");
    }

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    /** The invite token never leaves the internal outbox; the journeys read it there. */
    private String inviteToken(String email) {
        return jdbc.sql("SELECT payload->>'invite_token' FROM outbox_events WHERE event_type = 'member.invite_email' "
                + "AND payload->>'email' = :email ORDER BY created_at DESC LIMIT 1")
            .param("email", email).query(String.class).single();
    }
}
