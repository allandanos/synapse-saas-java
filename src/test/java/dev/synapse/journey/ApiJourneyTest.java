package dev.synapse.journey;

import static dev.synapse.support.ProblemAssert.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * The milestone-2 journeys over a real Postgres: auth → org → invite → accept →
 * roles → API keys → operator suspension, plus the problem-document contract.
 * Mirrors the reference's conformance modules and its integration edge cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "synapse.bootstrap-admin-email=" + ApiJourneyTest.ADMIN_EMAIL,
    "synapse.bootstrap-admin-password=" + ApiJourneyTest.ADMIN_PASSWORD,
    "synapse.refresh-reuse-grace-seconds=0"
})
class ApiJourneyTest extends PostgresTestSupport {

    static final String ADMIN_EMAIL = "operator@platform.example.com";
    static final String ADMIN_PASSWORD = "operator-password-12345";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    // ── Identity ─────────────────────────────────────────────────────────────────

    @Test
    void registerMeRefreshLogoutLoginAndReuseDetection() throws Exception {
        String email = "auth-" + ApiClient.uid() + "@conformance.example.com";
        Res reg = api.register(email, "Auth");
        assertThat(reg.status()).isEqualTo(201);
        assertThat(reg.body().at("/user/email").asText()).isEqualTo(email);
        assertThat(reg.body().at("/user/is_platform_admin").asBoolean()).isFalse();
        assertThat(reg.body().at("/tokens/token_type").asText()).isEqualTo("bearer");
        assertThat(reg.body().at("/tokens/expires_in").asInt()).isEqualTo(900);
        assertThat(reg.header("Set-Cookie")).contains("synapse_rt=").contains("HttpOnly").contains("SameSite=Lax");
        String access = reg.body().at("/tokens/access_token").asText();
        String refresh = reg.body().at("/tokens/refresh_token").asText();
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + access);

        Res me = api.get("/v1/auth/me", bearer);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("id")).isEqualTo(reg.body().at("/user/id").asText());
        assertThat(me.body().get("orgs").isArray()).isTrue();

        Res refreshed = api.post("/v1/auth/refresh", Map.of(), Map.of("refresh_token", refresh));
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.body().has("access_token") && refreshed.body().has("refresh_token") && refreshed.body().has("expires_in")).isTrue();
        String rotated = refreshed.text("refresh_token");
        assertThat(rotated).isNotEqualTo(refresh);

        // Grace is 0 in this test: replaying the rotated token is theft ⇒ the whole chain dies, and that survives the 401.
        assertProblem(api.post("/v1/auth/refresh", Map.of(), Map.of("refresh_token", refresh)), 401, "token reuse detected");
        assertProblem(api.post("/v1/auth/refresh", Map.of(), Map.of("refresh_token", rotated)), 401, "token reuse detected"); // the successor died with the chain
        assertProblem(api.post("/v1/auth/refresh", Map.of(), Map.of()), 401, "unauthorized");

        assertThat(api.post("/v1/auth/logout", bearer, null).status()).isEqualTo(204);

        Res login = api.post("/v1/auth/login", Map.of(), Map.of("email", email, "password", ApiClient.PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().at("/user/last_login_at").isNull()).isFalse();
        assertProblem(api.post("/v1/auth/login", Map.of(), Map.of("email", email, "password", "wrong-password-1")), 401, "invalid credentials");
        assertProblem(api.post("/v1/auth/login", Map.of(), Map.of("email", "nobody-" + ApiClient.uid() + "@example.com", "password", "wrong-password-1")), 401, "invalid credentials");
        assertProblem(api.register(email.toUpperCase(), "Dup"), 409, "email already registered"); // citext: case-insensitive uniqueness
    }

    @Test
    void passwordResetTravelsByTheInternalOutboxOnly() throws Exception {
        Tenant tenant = api.makeTenant("reset");
        assertThat(api.post("/v1/auth/forgot-password", Map.of(), Map.of("email", tenant.email())).status()).isEqualTo(202);
        assertThat(api.post("/v1/auth/forgot-password", Map.of(), Map.of("email", "nobody-" + ApiClient.uid() + "@example.com")).status()).isEqualTo(202);
        assertProblem(api.post("/v1/auth/reset-password", Map.of(), Map.of("token", "not-a-token", "password", ApiClient.PASSWORD)), 401);

        Map<String, Object> event = outboxPayload("user.password_reset_link", UUID.fromString(tenant.userId()));
        assertThat(event).containsEntry("email", tenant.email()).containsKey("token");
        assertThat(audienceOf("user.password_reset_link", UUID.fromString(tenant.userId()))).isEqualTo("internal");

        Res reset = api.post("/v1/auth/reset-password", Map.of(), Map.of("token", event.get("token"), "password", "new-password-12345"));
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.body().at("/tokens/access_token").asText()).isNotBlank();
        // every previous session died with the password
        assertProblem(api.post("/v1/auth/refresh", Map.of(), Map.of("refresh_token", tenant.refreshToken())), 401);
        assertThat(api.post("/v1/auth/login", Map.of(), Map.of("email", tenant.email(), "password", "new-password-12345")).status()).isEqualTo(200);
        assertProblem(api.post("/v1/auth/reset-password", Map.of(), Map.of("token", event.get("token"), "password", "new-password-12345")), 401);
    }

    // ── Problem documents ────────────────────────────────────────────────────────

    @Test
    void everyErrorIsAProblemDocument() throws Exception {
        Tenant tenant = api.makeTenant("problems");

        JsonNode missing = assertProblem(api.get("/v1/auth/me", Map.of("X-Request-Id", "my-req-123")), 401, "unauthorized");
        assertThat(missing.get("request_id").asText()).isEqualTo("my-req-123");
        assertThat(missing.get("detail").asText()).isEqualTo("Missing bearer token");
        assertThat(missing.get("instance").asText()).isEqualTo("/v1/auth/me");
        assertProblem(api.get("/v1/auth/me", Map.of("Authorization", "Bearer garbage")), 401, "unauthorized");
        assertProblem(api.get("/v1/auth/me", Map.of("Authorization", "Bearer sk_garbage")), 401, "unauthorized");

        Res validation = api.post("/v1/auth/register", Map.of(), Map.of("email", "not-an-email", "password", "x"));
        JsonNode doc = assertProblem(validation, 422, "validation failed");
        assertThat(doc.get("detail").asText()).isEqualTo("Invalid request: email, password, display_name");
        List<String> locs = doc.get("errors").findValues("loc").stream().map(JsonNode::toString).toList();
        assertThat(locs).containsExactly("[\"body\",\"email\"]", "[\"body\",\"password\"]", "[\"body\",\"display_name\"]"); // declaration order, wire names
        assertThat(doc.get("errors").findValues("type").stream().map(JsonNode::asText)).contains("missing", "value_error");

        assertProblem(api.post("/v1/auth/register", Map.of(), "{bad"), 422, "validation failed");
        assertProblem(api.post("/v1/auth/register", Map.of(), null), 422, "validation failed");
        assertProblem(api.get("/v1/orgs/current/members?limit=0", tenant.headers()), 422, "validation failed");
        JsonNode badLimit = api.get("/v1/orgs/current/members?limit=0", tenant.headers()).body();
        assertThat(badLimit.at("/errors/0/loc").toString()).isEqualTo("[\"query\",\"limit\"]");
        assertThat(badLimit.at("/errors/0/msg").asText()).isEqualTo("Input should be greater than or equal to 1");
        assertThat(badLimit.at("/errors/0/type").asText()).isEqualTo("greater_than_equal");
        JsonNode badUuid = assertProblem(api.patch("/v1/memberships/not-a-uuid", tenant.headers(), Map.of("status", "active")), 422, "validation failed");
        assertThat(badUuid.at("/errors/0/loc").toString()).isEqualTo("[\"path\",\"membership_id\"]");
        assertProblem(api.patch("/v1/orgs/current", tenant.headers(), Map.of("name", "R")), 422, "validation failed");

        assertProblem(api.get("/v1/nothing", tenant.bearer()), 404, "not found");
        assertProblem(api.get("/v1/nothing", Map.of()), 404, "not found"); // unknown paths are 404 even without a credential
        assertProblem(api.delete("/v1/orgs", tenant.bearer()), 405, "method not allowed");
        assertProblem(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + tenant.accessToken(), "X-Org-Id", "nope")), 404, "not found");
        assertProblem(api.get("/v1/orgs/current", tenant.bearer()), 404, "not found");
        assertProblem(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + tenant.accessToken(), "X-Org-Id", UUID.randomUUID().toString())), 404, "not found");
    }

    // ── Tenancy + authorization + API keys ───────────────────────────────────────

    @Test
    void orgInviteAcceptRolesAndApiKeysJourney() throws Exception {
        Tenant owner = api.makeTenant("owner");

        Res orgs = api.get("/v1/orgs", owner.bearer());
        assertThat(orgs.status()).isEqualTo(200);
        assertThat(orgs.body().at("/meta/total").asInt()).isEqualTo(1);
        assertThat(orgs.body().at("/data/0/id").asText()).isEqualTo(owner.orgId());

        assertThat(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + owner.accessToken(), "X-Org-Slug", owner.slug())).status()).isEqualTo(200);
        assertThat(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + owner.accessToken(), "Host", owner.slug() + ".localhost:8080")).status()).isEqualTo(200);
        assertProblem(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + owner.accessToken(), "Host", "127.0.0.1:8080")), 404, "not found"); // an IP literal is not a slug
        Res switched = api.post("/v1/auth/switch-org", owner.bearer(), Map.of("organization_id", owner.orgId()));
        assertThat(switched.status()).isEqualTo(200);
        assertThat(switched.header("Set-Cookie")).contains("synapse_rt="); // the rotated refresh token travels in the cookie
        assertThat(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + switched.text("access_token"))).text("slug")).isEqualTo(owner.slug());
        assertProblem(api.post("/v1/auth/switch-org", owner.bearer(), Map.of("organization_id", UUID.randomUUID().toString())), 404);

        Res renamed = api.patch("/v1/orgs/current", owner.headers(), Map.of("name", "Renamed", "settings", Map.of("a", 1)));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.text("name")).isEqualTo("Renamed");
        assertThat(renamed.body().at("/settings/a").asInt()).isEqualTo(1);
        assertProblem(api.post("/v1/orgs", owner.bearer(), Map.of("name", "Reserved", "slug", "admin")), 409, "slug unavailable");
        assertThat(api.post("/v1/orgs", owner.bearer(), Map.of("name", "Taken", "slug", owner.slug())).text("slug")).matches("taken-[0-9a-f]{6}"); // taken slug ⇒ unique suffix

        Res members = api.get("/v1/orgs/current/members", owner.headers());
        assertThat(members.body().at("/meta/total").asInt()).isEqualTo(1);
        assertThat(members.body().at("/data/0/role_keys/0").asText()).isEqualTo("owner");
        assertThat(members.body().at("/data/0/email").asText()).isEqualTo(owner.email());

        // Invite: 201, status invited, no token in the body; the token rides the internal outbox only.
        String inviteeEmail = "invitee-" + ApiClient.uid() + "@conformance.example.com";
        Res invited = api.post("/v1/orgs/current/members/invite", owner.headers(), Map.of("email", inviteeEmail, "role_keys", List.of("member")));
        assertThat(invited.status()).isEqualTo(201);
        assertThat(invited.text("status")).isEqualTo("invited");
        assertThat(invited.body().toString()).doesNotContain("invite_token");
        assertThat(invited.body().get("role_keys").get(0).asText()).isEqualTo("member");
        String membershipId = invited.text("id");
        UUID membershipUuid = UUID.fromString(membershipId);
        assertThat(outboxPayload("member.invited", membershipUuid)).doesNotContainKey("invite_token").containsEntry("email", inviteeEmail);
        assertThat(audienceOf("member.invited", membershipUuid)).isEqualTo("public");
        Map<String, Object> mail = outboxPayload("member.invite_email", membershipUuid);
        assertThat(audienceOf("member.invite_email", membershipUuid)).isEqualTo("internal");
        String inviteToken = (String) mail.get("invite_token");
        assertThat(inviteToken).isNotBlank();
        JsonNode dupInvite = assertProblem(api.post("/v1/orgs/current/members/invite", owner.headers(), Map.of("email", inviteeEmail)), 409, "conflict");
        assertThat(dupInvite.get("membership_status").asText()).isEqualTo("invited");
        assertThat(outboxPayload("member.invited", membershipUuid)).containsEntry("org_name", "Renamed"); // the mail names the org
        assertProblem(api.post("/v1/orgs/current/members/invite", owner.headers(), Map.of("email", "x-" + ApiClient.uid() + "@example.com", "role_keys", List.of("nope"))), 404, "role not found");
        assertThat(api.get("/v1/orgs/current/members?limit=1", owner.headers()).body().at("/meta/total").asInt()).isEqualTo(2);

        // The invitee registers, accepts with the emailed token, and is now an active member with the member role.
        Res inviteeReg = api.register(inviteeEmail, "Invitee");
        Map<String, String> inviteeBearer = Map.of("Authorization", "Bearer " + inviteeReg.body().at("/tokens/access_token").asText());
        assertProblem(api.post("/v1/auth/accept-invite", inviteeBearer, Map.of("token", "not-a-token")), 404, "invite not found");
        assertProblem(api.post("/v1/auth/accept-invite", inviteeBearer, Map.of("token", "short")), 422, "validation failed");
        Res accepted = api.post("/v1/auth/accept-invite", inviteeBearer, Map.of("token", inviteToken));
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.text("organization_id")).isEqualTo(owner.orgId());
        assertThat(accepted.text("status")).isEqualTo("active");
        assertProblem(api.post("/v1/auth/accept-invite", inviteeBearer, Map.of("token", inviteToken)), 404, "invite not found"); // single use
        Res inviteeMe = api.get("/v1/auth/me", inviteeBearer);
        assertThat(inviteeMe.body().at("/orgs/0/id").asText()).isEqualTo(owner.orgId());
        assertThat(inviteeMe.body().at("/orgs/0/role_keys/0").asText()).isEqualTo("member");

        Map<String, String> inviteeHeaders = new HashMap<>(inviteeBearer);
        inviteeHeaders.put("X-Org-Id", owner.orgId());
        assertThat(api.get("/v1/orgs/current", inviteeHeaders).status()).isEqualTo(200);
        JsonNode denied = assertProblem(api.post("/v1/orgs/current/members/invite", inviteeHeaders, Map.of("email", "z@example.com")), 403, "permission denied");
        assertThat(denied.get("permission").asText()).isEqualTo("member:invite");

        // Promote to developer; the denormalised permission set is visible to the very next request.
        Res promoted = api.patch("/v1/memberships/" + membershipId, owner.headers(), Map.of("role_keys", List.of("developer")));
        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.body().get("role_keys").get(0).asText()).isEqualTo("developer");
        assertThat(promoted.text("email")).isEqualTo(inviteeEmail);

        // API keys are bounded by their creator (ADR 0008).
        Res devKey = api.post("/v1/api-keys", inviteeHeaders, Map.of("name", "dev key"));
        assertThat(devKey.status()).isEqualTo(201);
        assertThat(devKey.text("key")).startsWith("sk_").hasSizeGreaterThan(40);
        assertThat(devKey.text("prefix")).isEqualTo(devKey.text("key").substring(0, 8));
        List<String> devScopes = json.convertValue(devKey.body().get("scopes"), json.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(devScopes).containsExactly("agents:read", "apikey:manage", "member:read", "org:read", "project:manage", "project:read", "usage:read", "webhook:manage");
        JsonNode escalate = assertProblem(api.post("/v1/api-keys", inviteeHeaders, Map.of("name", "escalate", "scopes", List.of("org:delete"))), 403, "permission denied");
        assertThat(escalate.get("exceeds_creator").get(0).asText()).isEqualTo("org:delete");
        JsonNode unknown = assertProblem(api.post("/v1/api-keys", inviteeHeaders, Map.of("name", "x", "scopes", List.of("nope:nope"))), 403, "permission denied");
        assertThat(unknown.get("unknown").get(0).asText()).isEqualTo("nope:nope");

        Map<String, String> asKey = Map.of("Authorization", "Bearer " + devKey.text("key"));
        assertThat(api.get("/v1/orgs/current", asKey).text("id")).isEqualTo(owner.orgId()); // pinned tenant, no X-Org-Id
        assertThat(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + devKey.text("key"), "X-Org-Id", UUID.randomUUID().toString())).text("id")).isEqualTo(owner.orgId());
        JsonNode outOfScope = assertProblem(api.post("/v1/orgs/current/members/invite", asKey, Map.of("email", "k@example.com")), 403, "permission denied");
        assertThat(outOfScope.get("auth").asText()).isEqualTo("api_key");
        Res childKey = api.post("/v1/api-keys", asKey, Map.of("name", "child")); // a key minting a key is rooted at the human
        assertThat(childKey.status()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT actor_type FROM audit_logs WHERE event_type = 'api_key.created' AND target_id = :id")
            .param("id", UUID.fromString(childKey.text("id"))).query(String.class).single()).isEqualTo("api_key");
        assertThat(jdbc.sql("SELECT actor_user_id::text FROM audit_logs WHERE event_type = 'api_key.created' AND target_id = :id")
            .param("id", UUID.fromString(childKey.text("id"))).query(String.class).single()).isEqualTo(inviteeReg.body().at("/user/id").asText());

        Res listed = api.get("/v1/api-keys", inviteeHeaders);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.header("X-Total-Count")).isEqualTo("2");
        assertThat(listed.body().toString()).doesNotContain(devKey.text("key"));
        assertThat(api.get("/v1/api-keys?limit=1", inviteeHeaders).body().size()).isEqualTo(1);

        // Demoting the creator shrinks every key they minted, immediately.
        assertThat(api.patch("/v1/memberships/" + membershipId, owner.headers(), Map.of("role_keys", List.of("member"))).status()).isEqualTo(200);
        JsonNode shrunk = assertProblem(api.get("/v1/api-keys", asKey), 403, "permission denied");
        assertThat(shrunk.get("reason").asText()).isEqualTo("creator_lacks_permission");

        // Revoke ⇒ the key answers 401 everywhere.
        assertThat(api.delete("/v1/api-keys/" + devKey.text("id"), owner.headers()).status()).isEqualTo(204);
        assertProblem(api.get("/v1/orgs/current", asKey), 401, "unauthorized");
        assertProblem(api.delete("/v1/api-keys/" + UUID.randomUUID(), owner.headers()), 404, "api key not found");

        // Cross-tenant rows are 404, the owner cannot be removed, removal is 204 then 404.
        Tenant other = api.makeTenant("other");
        String otherMembership = api.get("/v1/orgs/current/members", other.headers()).body().at("/data/0/id").asText();
        assertProblem(api.patch("/v1/memberships/" + otherMembership, owner.headers(), Map.of("role_keys", List.of("member"))), 404, "not found");
        String ownerMembership = api.get("/v1/orgs/current/members", owner.headers()).body().at("/data/0/id").asText();
        assertProblem(api.delete("/v1/memberships/" + ownerMembership, owner.headers()), 404, "not found");
        assertThat(api.delete("/v1/memberships/" + membershipId, owner.headers()).status()).isEqualTo(204);
        assertProblem(api.delete("/v1/memberships/" + membershipId, owner.headers()), 404, "not found");
        assertProblem(api.get("/v1/orgs/current", inviteeHeaders), 404, "not found");
    }

    @Test
    void customRolesAndTheCatalog() throws Exception {
        Tenant tenant = api.makeTenant("roles");
        Res permissions = api.get("/v1/permissions", Map.of());
        assertThat(permissions.status()).isEqualTo(200);
        assertThat(permissions.body().size()).isEqualTo(21);

        Res roles = api.get("/v1/roles", tenant.headers());
        assertThat(roles.body().findValues("key").stream().map(JsonNode::asText)).contains("owner", "admin", "billing", "developer", "member");
        String ownerRoleId = roles.body().findValues("id").get(roles.body().findValues("key").stream().map(JsonNode::asText).toList().indexOf("owner")).asText();
        assertProblem(api.delete("/v1/roles/" + ownerRoleId, tenant.headers()), 404, "role not found");

        String key = "auditor_" + ApiClient.uid();
        Res created = api.post("/v1/roles", tenant.headers(), Map.of("key", key, "name", "Auditor", "permissions", List.of("org:read", "audit:read")));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().findValues("permissions").get(0).toString()).isEqualTo("[\"audit:read\",\"org:read\"]");
        assertThat(created.body().get("is_system").asBoolean()).isFalse();
        String roleId = created.text("id");

        Res patched = api.patch("/v1/roles/" + roleId, tenant.headers(), Map.of("permissions", List.of("org:read"), "name", "Reader"));
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body().get("permissions").toString()).isEqualTo("[\"org:read\"]");
        assertThat(patched.text("name")).isEqualTo("Reader");

        assertProblem(api.post("/v1/roles", tenant.headers(), Map.of("key", "x_" + ApiClient.uid(), "name", "X", "permissions", List.of("nope:nope"))), 422, "validation failed");
        JsonNode unknown = assertProblem(api.post("/v1/roles", tenant.headers(), Map.of("key", "x_" + ApiClient.uid(), "name", "Valid", "permissions", List.of("nope:nope"))), 403, "permission denied");
        assertThat(unknown.get("unknown").get(0).asText()).isEqualTo("nope:nope");
        assertProblem(api.post("/v1/roles", tenant.headers(), Map.of("key", "Bad-Key", "name", "Valid", "permissions", List.of())), 422, "validation failed");
        JsonNode dupKey = assertProblem(api.post("/v1/roles", tenant.headers(), Map.of("key", key, "name", "Again", "permissions", List.of())), 409, "conflict");
        assertThat(dupKey.get("key").asText()).isEqualTo(key);

        // A member holding the custom role sees permission edits immediately.
        String email = "holder-" + ApiClient.uid() + "@conformance.example.com";
        Res holderReg = api.register(email, "Holder");
        String membershipId = api.post("/v1/orgs/current/members/invite", tenant.headers(), Map.of("email", email, "role_keys", List.of(key))).text("id");
        String token = (String) outboxPayload("member.invite_email", UUID.fromString(membershipId)).get("invite_token");
        Map<String, String> holderBearer = Map.of("Authorization", "Bearer " + holderReg.body().at("/tokens/access_token").asText());
        assertThat(api.post("/v1/auth/accept-invite", holderBearer, Map.of("token", token)).status()).isEqualTo(200);
        Map<String, String> holder = Map.of("Authorization", holderBearer.get("Authorization"), "X-Org-Id", tenant.orgId());
        assertThat(api.get("/v1/orgs/current", holder).status()).isEqualTo(200);
        assertProblem(api.get("/v1/orgs/current/members", holder), 403, "permission denied");
        assertThat(api.patch("/v1/roles/" + roleId, tenant.headers(), Map.of("permissions", List.of("org:read", "member:read"))).status()).isEqualTo(200);
        assertThat(api.get("/v1/orgs/current/members", holder).status()).isEqualTo(200);

        assertThat(api.delete("/v1/roles/" + roleId, tenant.headers()).status()).isEqualTo(204);
        assertProblem(api.delete("/v1/roles/" + roleId, tenant.headers()), 404, "role not found");
        assertProblem(api.get("/v1/orgs/current/members", holder), 403, "permission denied"); // recomputed after the role vanished
    }

    // ── Operator surface (ADR 0008) ──────────────────────────────────────────────

    @Test
    void operatorCanSuspendAndUnsuspendAndTenantsCannot() throws Exception {
        Tenant tenant = api.makeTenant("suspend");
        Res login = api.post("/v1/auth/login", Map.of(), Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD));
        assertThat(login.status()).as("bootstrap operator can log in: %s", login.body()).isEqualTo(200);
        assertThat(login.body().at("/user/is_platform_admin").asBoolean()).isTrue();
        Map<String, String> platform = Map.of("Authorization", "Bearer " + login.body().at("/tokens/access_token").asText());
        String key = api.post("/v1/api-keys", tenant.headers(), Map.of("name", "ci", "scopes", List.of("org:read"))).text("key");

        assertProblem(api.post("/v1/orgs/" + tenant.orgId() + "/suspend", tenant.headers(), null), 404, "not found");
        assertProblem(api.post("/v1/orgs/" + UUID.randomUUID() + "/suspend", platform, null), 404, "not found");
        assertThat(api.post("/v1/orgs/" + tenant.orgId() + "/suspend", platform, null).status()).isEqualTo(204);

        JsonNode doc = assertProblem(api.get("/v1/orgs/current", tenant.headers()), 403, "organization suspended");
        assertThat(doc.get("organization_id").asText()).isEqualTo(tenant.orgId());
        assertThat(doc.get("organization_status").asText()).isEqualTo("suspended");
        assertProblem(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + key)), 401, "unauthorized");
        Tenant stranger = api.makeTenant("stranger");
        assertProblem(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + stranger.accessToken(), "X-Org-Id", tenant.orgId())), 404, "not found");
        Res asOperator = api.get("/v1/orgs/current", Map.of("Authorization", platform.get("Authorization"), "X-Org-Id", tenant.orgId()));
        assertThat(asOperator.status()).isEqualTo(200);
        assertThat(asOperator.text("status")).isEqualTo("suspended");

        assertThat(api.delete("/v1/orgs/" + tenant.orgId() + "/suspend", platform).status()).isEqualTo(204);
        assertThat(api.get("/v1/orgs/current", tenant.headers()).status()).isEqualTo(200);
        assertThat(api.get("/v1/orgs/current", Map.of("Authorization", "Bearer " + key)).status()).isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> outboxPayload(String eventType, UUID aggregateId) throws Exception {
        String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE event_type = :type AND aggregate_id = :id ORDER BY created_at DESC LIMIT 1")
            .param("type", eventType).param("id", aggregateId).query(String.class).single();
        return json.readValue(payload, Map.class);
    }

    private String audienceOf(String eventType, UUID aggregateId) {
        return jdbc.sql("SELECT audience FROM outbox_events WHERE event_type = :type AND aggregate_id = :id ORDER BY created_at DESC LIMIT 1")
            .param("type", eventType).param("id", aggregateId).query(String.class).single();
    }
}
