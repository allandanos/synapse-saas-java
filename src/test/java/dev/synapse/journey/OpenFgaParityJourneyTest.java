package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.authorization.PermissionDef;
import dev.synapse.authorization.fga.FgaClient;
import dev.synapse.authorization.fga.FgaModel;
import dev.synapse.authorization.fga.FgaTuple;
import dev.synapse.authorization.fga.TupleSync;
import dev.synapse.core.cache.Caches;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.ApiClient.Tenant;
import dev.synapse.support.PostgresTestSupport;
import dev.synapse.worker.jobs.OutboxDispatchJob;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Against a REAL OpenFGA (reference: {@code tests/integration/test_openfga_parity.py}).
 *
 * <ol>
 *   <li>the generated model answers every system role × permission exactly like RBAC;</li>
 *   <li>with {@code SYNAPSE_AUTHZ_BACKEND=openfga} a route is gated by the store,
 *       and the tuple sync (outbox → worker) converges after a role change.</li>
 * </ol>
 *
 * <p>Every run creates its own store: the local server is shared with the other
 * ports, so nothing here reads or writes anyone else's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("fgaAvailable")
class OpenFgaParityJourneyTest extends PostgresTestSupport {

    static final String FGA_URL = System.getenv().getOrDefault("SYNAPSE_OPENFGA_URL", "http://localhost:8081");

    private static String storeId;
    private static String modelId;

    static boolean fgaAvailable() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(HttpRequest.newBuilder(URI.create(FGA_URL + "/healthz")).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** A client bound to whatever store/model the argument names (used before the context exists too). */
    private static FgaClient clientFor(String store, String model) {
        SynapseProperties props = mock(SynapseProperties.class);
        when(props.openfgaUrl()).thenReturn(FGA_URL);
        when(props.openfgaStoreId()).thenReturn(store);
        when(props.openfgaModelId()).thenReturn(model);
        when(props.openfgaApiToken()).thenReturn("");
        return new FgaClient(props, new ObjectMapper());
    }

    @DynamicPropertySource
    static void openfga(DynamicPropertyRegistry registry) {
        if (storeId == null) {
            FgaClient bootstrap = clientFor("", "");
            storeId = bootstrap.createStore("synapse-java-test-" + UUID.randomUUID().toString().substring(0, 8));
            bootstrap.storeId(storeId);
            modelId = bootstrap.writeModel(FgaModel.build());
        }
        registry.add("synapse.authz-backend", () -> "openfga");
        registry.add("synapse.openfga-url", () -> FGA_URL);
        registry.add("synapse.openfga-store-id", () -> storeId);
        registry.add("synapse.openfga-model-id", () -> modelId);
        registry.add("synapse.openfga-fail-mode", () -> "closed");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired Caches caches;
    @Autowired OutboxDispatchJob outbox;
    @Autowired TupleSync tupleSync;

    ApiClient api;
    FgaClient store;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        store = clientFor(storeId, modelId);
    }

    /** The worker's fga decision cache is per-process and 30 s long; the store is the truth. */
    private void forgetDecisions(String userId, String orgId) {
        caches.fga().bump(userId + ":organization:" + orgId);
    }

    // ── 1. Model parity ──────────────────────────────────────────────────────────

    @Test
    void everyRoleTimesPermissionMatchesRbac() {
        String org = "organization:" + UUID.randomUUID();
        List<FgaTuple> writes = new ArrayList<>();
        Map<String, String> users = new java.util.LinkedHashMap<>();
        for (String role : FgaModel.ROLE_ORDER) {
            String user = "user:" + UUID.randomUUID();
            users.put(role, user);
            writes.add(new FgaTuple(user, role, org));
        }
        store.write(writes, List.of());

        List<String> mismatches = new ArrayList<>();
        for (String role : FgaModel.ROLE_ORDER) {
            Set<String> granted = Set.copyOf(PermissionCatalog.SYSTEM_ROLES.get(role).permissions());
            for (PermissionDef permission : PermissionCatalog.PERMISSIONS) {
                boolean allowed = store.check(users.get(role), FgaModel.relationFor(permission.key()), org);
                if (allowed != granted.contains(permission.key())) {
                    mismatches.add(role + " × " + permission.key() + " = " + allowed);
                }
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void directGrantsAndProjectInheritanceAndSharing() {
        String org = "organization:" + UUID.randomUUID();
        String user = "user:" + UUID.randomUUID();
        String project = "project:" + UUID.randomUUID();
        store.write(List.of(
            new FgaTuple(user, "member", org),          // member: project:read only
            new FgaTuple(user, "can_audit_read", org),  // a custom role's direct grant
            new FgaTuple(org, "org", project)), List.of());

        assertThat(store.check(user, "can_audit_read", org)).isTrue();
        assertThat(store.check(user, "viewer", project)).isTrue();  // inherited via can_project_read from org
        assertThat(store.check(user, "editor", project)).isFalse();

        store.write(List.of(new FgaTuple(user, "editor", project)), List.of()); // shared explicitly
        assertThat(store.check(user, "editor", project)).isTrue();
    }

    @Test
    void duplicateWritesAndMissingDeletesAreTolerated() {
        String org = "organization:" + UUID.randomUUID();
        String user = "user:" + UUID.randomUUID();
        FgaTuple tuple = new FgaTuple(user, "owner", org);
        store.write(List.of(tuple), List.of());
        store.write(List.of(tuple), List.of());                      // duplicate write
        store.write(List.of(), List.of(new FgaTuple(user, "admin", org))); // delete of a tuple that never existed
        assertThat(store.readTuples(org)).contains(tuple);
    }

    // ── 2. End to end ────────────────────────────────────────────────────────────

    @Test
    void aRouteIsGatedByTheStoreAndTheSyncConverges() throws Exception {
        Tenant owner = api.makeTenant("fga-owner");
        String ownerUser = "user:" + owner.userId();
        String orgObject = "organization:" + owner.orgId();

        // Creating the org converged the owner's tuples right after the commit,
        // so the very next request is already answered by the store.
        assertThat(store.check(ownerUser, "owner", orgObject)).isTrue();
        assertThat(api.get("/v1/orgs/current/members", owner.headers()).status()).isEqualTo(200);

        // The worker's pass over the same authz.tuples_changed event finds an empty diff
        outbox.runOnce();
        assertThat(store.readTuples(orgObject).stream().filter(t -> t.user().equals(ownerUser)).toList())
            .containsExactly(new FgaTuple(ownerUser, "owner", orgObject));

        // Take the tuples away behind the API's back: `closed` denies, RBAC notwithstanding
        store.write(List.of(), List.of(new FgaTuple(ownerUser, "owner", orgObject)));
        forgetDecisions(owner.userId(), owner.orgId());
        assertThat(api.get("/v1/orgs/current/members", owner.headers()).status()).isEqualTo(403);

        // `authz fga sync` repairs it (what the CLI does for a whole org)
        outbox.runOnce(); // nothing queued: the repair is the sync's job, not the outbox's
        assertThat(api.get("/v1/orgs/current/members", owner.headers()).status()).isEqualTo(403);
        tupleSync.apply(Map.of("organization_id", owner.orgId(), "user_id", owner.userId()));
        forgetDecisions(owner.userId(), owner.orgId());
        assertThat(api.get("/v1/orgs/current/members", owner.headers()).status()).isEqualTo(200);

        // A member joins as `member`; the store says so and nothing more
        String email = "fga-dev-" + ApiClient.uid() + "@conformance.example.com";
        Res invite = api.post("/v1/orgs/current/members/invite", owner.headers(), Map.of("email", email));
        assertThat(invite.status()).isEqualTo(201);
        Res registered = api.register(email, "Dev");
        String inviteToken = jdbc.sql("SELECT payload->>'invite_token' FROM outbox_events "
                + "WHERE event_type = 'member.invite_email' AND payload->>'email' = :email ORDER BY created_at DESC LIMIT 1")
            .param("email", email).query(String.class).single();
        Res accepted = api.post("/v1/auth/accept-invite",
            Map.of("Authorization", "Bearer " + registered.body().at("/tokens/access_token").asText()),
            Map.of("token", inviteToken));
        assertThat(accepted.status()).isEqualTo(200);
        outbox.runOnce();

        String devId = registered.body().at("/user/id").asText();
        assertThat(store.check("user:" + devId, "member", "organization:" + owner.orgId())).isTrue();
        assertThat(store.check("user:" + devId, "can_org_delete", "organization:" + owner.orgId())).isFalse();

        // Promote to admin ⇒ resync ⇒ the store swaps the role tuples
        String membershipId = invite.text("id");
        assertThat(api.patch("/v1/memberships/" + membershipId, owner.headers(),
            Map.of("role_keys", List.of("admin"))).status()).isEqualTo(200);
        outbox.runOnce();
        assertThat(store.check("user:" + devId, "admin", "organization:" + owner.orgId())).isTrue();
        assertThat(store.check("user:" + devId, "member", "organization:" + owner.orgId())).isFalse();

        // Removal takes every tuple with it
        assertThat(api.delete("/v1/memberships/" + membershipId, owner.headers()).status()).isEqualTo(204);
        outbox.runOnce();
        assertThat(store.readTuples("organization:" + owner.orgId()).stream()
            .filter(t -> t.user().equals("user:" + devId)).toList()).isEmpty();

        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE dead_at IS NOT NULL AND organization_id = :org")
            .param("org", UUID.fromString(owner.orgId())).query(Long.class).single()).isZero();
    }

    @Test
    void aCustomRolesPermissionsBecomeDirectTuples() throws Exception {
        Tenant owner = api.makeTenant("fga-custom");
        outbox.runOnce();
        forgetDecisions(owner.userId(), owner.orgId());

        Res role = api.post("/v1/roles", owner.headers(), Map.of(
            "key", "auditor", "name", "Auditor", "permissions", List.of("audit:read")));
        assertThat(role.status()).isEqualTo(201);

        String email = "fga-aud-" + ApiClient.uid() + "@conformance.example.com";
        Res invite = api.post("/v1/orgs/current/members/invite", owner.headers(),
            Map.of("email", email, "role_keys", List.of("member", "auditor")));
        assertThat(invite.status()).isEqualTo(201);
        Res registered = api.register(email, "Auditor");
        String inviteToken = jdbc.sql("SELECT payload->>'invite_token' FROM outbox_events "
                + "WHERE event_type = 'member.invite_email' AND payload->>'email' = :email ORDER BY created_at DESC LIMIT 1")
            .param("email", email).query(String.class).single();
        api.post("/v1/auth/accept-invite",
            Map.of("Authorization", "Bearer " + registered.body().at("/tokens/access_token").asText()),
            Map.of("token", inviteToken));
        outbox.runOnce();

        String auditorId = registered.body().at("/user/id").asText();
        // The system role becomes a role tuple; the custom role's extra permission a direct grant
        assertThat(store.check("user:" + auditorId, "member", "organization:" + owner.orgId())).isTrue();
        assertThat(store.check("user:" + auditorId, "can_audit_read", "organization:" + owner.orgId())).isTrue();
        assertThat(store.check("user:" + auditorId, "can_billing_manage", "organization:" + owner.orgId())).isFalse();
    }

    @Test
    void anApiKeyPrincipalNeverConsultsTheStore() throws Exception {
        Tenant owner = api.makeTenant("fga-key");
        outbox.runOnce();
        forgetDecisions(owner.userId(), owner.orgId());

        Res key = api.post("/v1/api-keys", owner.headers(), Map.of("name", "ci", "scopes", List.of("org:read")));
        assertThat(key.status()).isEqualTo(201);
        String secret = key.text("key");

        // The key's tuples are never written, yet its scopes (bounded by the creator's RBAC) still authorise
        assertThat(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + secret)).status()).isEqualTo(200);
        assertThat(store.readTuples("organization:" + owner.orgId()).stream()
            .anyMatch(t -> t.relation().equals("can_org_read") && t.user().startsWith("user:"))).isFalse();
    }
}
