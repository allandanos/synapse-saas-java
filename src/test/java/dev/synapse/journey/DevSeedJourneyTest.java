package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.bootstrap.DevSeeder;
import dev.synapse.identity.UserRepository;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
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
 * The dev seed the console's Playwright journeys log in against
 * ({@code owner@acme.example.com} / {@code password123}): every demo user can
 * authenticate, the owner is a platform admin and owns org {@code acme}, each
 * remaining system role has an accepted membership, and a second run is a no-op.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "synapse.worker-enabled=false")
class DevSeedJourneyTest extends PostgresTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DevSeeder seeder;
    @Autowired UserRepository users;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        seeder.seed();
    }

    @Test
    void seedsOneLoginableUserPerSystemRoleInTheAcmeOrg() throws Exception {
        for (String roleKey : DevSeeder.ROLE_KEYS) {
            String email = DevSeeder.emailFor(roleKey);
            Res login = api.post("/v1/auth/login", Map.of(), Map.of("email", email, "password", DevSeeder.PASSWORD));
            assertThat(login.status()).as("login %s", email).isEqualTo(200);

            Res me = api.get("/v1/auth/me", Map.of("Authorization", "Bearer " + login.body().get("tokens").get("access_token").asText()));
            assertThat(me.status()).isEqualTo(200);
            assertThat(me.body().get("display_name").asText()).isEqualTo(DevSeeder.displayNameFor(roleKey));
            assertThat(me.body().get("is_platform_admin").asBoolean()).isEqualTo(roleKey.equals("owner"));

            JsonNode orgs = me.body().get("orgs");
            assertThat(orgs).hasSize(1);
            assertThat(orgs.get(0).get("slug").asText()).isEqualTo(DevSeeder.ORG_SLUG);
            List<String> roleKeys = json.convertValue(orgs.get(0).get("role_keys"), List.class);
            assertThat(roleKeys).as("role_keys of %s", email).containsExactly(roleKey);
        }
    }

    @Test
    void ownerOrgIsCreatedThroughTheNormalPathSoEntitlementsResolve() throws Exception {
        Res login = api.post("/v1/auth/login", Map.of(),
            Map.of("email", DevSeeder.OWNER_EMAIL, "password", DevSeeder.PASSWORD));
        String token = login.body().get("tokens").get("access_token").asText();
        String orgId = api.get("/v1/auth/me", Map.of("Authorization", "Bearer " + token))
            .body().get("orgs").get(0).get("id").asText();
        Map<String, String> headers = Map.of("Authorization", "Bearer " + token, "X-Org-Id", orgId);

        Res entitlements = api.get("/v1/entitlements", headers);
        assertThat(entitlements.status()).isEqualTo(200);
        assertThat(entitlements.body().get("plan_key").asText()).isEqualTo("free");
        // The seed grants the seats its five demo users need; the free plan ships three.
        assertThat(entitlements.body().get("limits").get("users").get("value").asLong()).isEqualTo(DevSeeder.SEAT_LIMIT);

        // The console's members page reads {data, meta} from this route; all five demo users are active.
        Res members = api.get("/v1/orgs/current/members", headers);
        assertThat(members.status()).isEqualTo(200);
        assertThat(members.body().get("data")).hasSize(DevSeeder.ROLE_KEYS.size());
        assertThat(members.body().get("meta").get("total").asInt()).isEqualTo(DevSeeder.ROLE_KEYS.size());
    }

    @Test
    void isIdempotent() {
        long before = users.findByEmail(DevSeeder.OWNER_EMAIL).stream().count();
        String summary = seeder.seed();
        assertThat(summary).contains("skipped");
        assertThat(users.findByEmail(DevSeeder.OWNER_EMAIL)).isPresent();
        assertThat(before).isEqualTo(1);
    }
}
