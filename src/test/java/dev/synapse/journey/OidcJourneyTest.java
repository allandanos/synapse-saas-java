package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.identity.oidc.JwksCache;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.PostgresTestSupport;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OIDC login against a stub identity provider (ADR 0010; reference:
 * {@code tests/integration/test_oidc_login.py}): PKCE round trip, id_token
 * verification, the three linking rules, single-use state, and the
 * cookie-then-refresh handoff that keeps tokens out of the URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OidcJourneyTest extends PostgresTestSupport {

    static final String REALM = "synapse";
    static final String CLIENT_ID = "synapse-web";
    static final String CERTS_PATH = "/realms/" + REALM + "/protocol/openid-connect/certs";
    static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";
    static final String WEB_ORIGIN = "http://localhost:3300";

    static final StubIdp IDP;
    static final KeyPair KEYS;

    static {
        try {
            KEYS = KeyPairGenerator.getInstance("RSA").genKeyPair();
            IDP = new StubIdp();
            IDP.certs(jwks("key-1", (RSAPublicKey) KEYS.getPublic()));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("synapse.identity-provider", () -> "keycloak");
        registry.add("synapse.keycloak-base-url", IDP::baseUrl);
        registry.add("synapse.keycloak-realm", () -> REALM);
        registry.add("synapse.keycloak-client-id", () -> CLIENT_ID);
        registry.add("synapse.keycloak-client-secret", () -> "dev-client-secret");
        registry.add("synapse.web-origin", () -> WEB_ORIGIN);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JwksCache jwks;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        jwks.clear();
        IDP.certs(jwks("key-1", (RSAPublicKey) KEYS.getPublic()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private record Start(String state, String nonce, String location) {}

    private Start start(String returnTo) throws Exception {
        MvcResult result = mvc.perform(get("/v1/auth/oidc/start" + (returnTo == null ? "" : "?return_to=" + returnTo)))
            .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getHeader("Location");
        Map<String, String> query = query(URI.create(location).getRawQuery());
        return new Start(query.get("state"), query.get("nonce"), location);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            values.put(java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return values;
    }

    private String idToken(KeyPair keys, String subject, String email, boolean emailVerified, String nonce) {
        return JWT.create()
            .withKeyId("key-1")
            .withIssuer(IDP.baseUrl() + "/realms/" + REALM)
            .withAudience(CLIENT_ID)
            .withSubject(subject)
            .withIssuedAt(new Date())
            .withExpiresAt(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
            .withClaim("email", email)
            .withClaim("email_verified", emailVerified)
            .withClaim("name", "SSO " + subject)
            .withClaim("nonce", nonce)
            .sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate()));
    }

    private static String jwks(String kid, RSAPublicKey key) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + kid + "\",\"n\":\""
            + encoder.encodeToString(key.getModulus().toByteArray()) + "\",\"e\":\""
            + encoder.encodeToString(key.getPublicExponent().toByteArray()) + "\"}]}";
    }

    private MvcResult callback(String code, String state) throws Exception {
        return mvc.perform(get("/v1/auth/oidc/callback?code=" + code + "&state=" + state)).andReturn();
    }

    // ── Journeys ─────────────────────────────────────────────────────────────────

    @Test
    void startSendsTheBrowserToTheIdpWithPkceAndASanitisedReturnTo() throws Exception {
        Start started = start("/settings/team");
        Map<String, String> query = query(URI.create(started.location()).getRawQuery());
        assertThat(started.location()).startsWith(IDP.baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/auth?");
        assertThat(query).containsEntry("client_id", CLIENT_ID)
            .containsEntry("response_type", "code")
            .containsEntry("scope", "openid email profile")
            .containsEntry("code_challenge_method", "S256");
        assertThat(query.get("code_challenge")).isNotBlank();
        assertThat(query.get("redirect_uri")).endsWith("/v1/auth/oidc/callback");

        // An off-site return_to is replaced by the default before it is ever stored
        Start offsite = start("//evil.example.com");
        assertThat(offsite.state()).isNotEqualTo(started.state());
    }

    @Test
    void aBrandNewSubjectBecomesAnSsoOnlyUserAndTheCookieMintsATokenPair() throws Exception {
        String email = "sso-new-" + ApiClient.uid() + "@conformance.example.com";
        Start started = start("/settings");
        IDP.token("{\"id_token\":\"" + idToken(KEYS, "sub-" + email, email, true, started.nonce()) + "\"}");

        MvcResult result = callback("code-1", started.state());
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(WEB_ORIGIN + "/auth/callback?return_to=/settings");

        Cookie cookie = result.getResponse().getCookie("synapse_rt");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        // Nothing token-shaped is ever in the URL
        assertThat(result.getResponse().getHeader("Location")).doesNotContain(cookie.getValue());

        // The console finishes the login by exchanging the cookie for an access token
        MvcResult refreshed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/v1/auth/refresh").cookie(cookie)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(refreshed.getResponse().getContentAsString()).get("access_token").asText()).isNotBlank();

        // The account has no local password: the password form points at SSO instead
        Res login = api.post("/v1/auth/login", Map.of(), Map.of("email", email, "password", ApiClient.PASSWORD));
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.body().get("sso_url").asText()).isEqualTo("/v1/auth/oidc/start");
        assertThat(login.body().get("identity_provider").asText()).isEqualTo("keycloak");

        assertThat(jdbc.sql("SELECT password_hash FROM users WHERE email = CAST(:e AS citext)")
            .param("e", email).query(String.class).optional()).isEmpty();
    }

    @Test
    void theSameSubjectLandsOnTheSameUserEvenWhenTheEmailChanges() throws Exception {
        String subject = "sub-stable-" + ApiClient.uid();
        String first = "sso-a-" + ApiClient.uid() + "@conformance.example.com";
        Start one = start(null);
        IDP.token("{\"id_token\":\"" + idToken(KEYS, subject, first, true, one.nonce()) + "\"}");
        assertThat(callback("c1", one.state()).getResponse().getStatus()).isEqualTo(302);

        Start two = start(null);
        IDP.token("{\"id_token\":\"" + idToken(KEYS, subject, "renamed-" + first, true, two.nonce()) + "\"}");
        assertThat(callback("c2", two.state()).getResponse().getStatus()).isEqualTo(302);

        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE provider_subject = :s").param("s", subject)
            .query(Long.class).single()).isEqualTo(1);
        // The stable link wins: the email on file is the one the account was created with
        assertThat(jdbc.sql("SELECT email FROM users WHERE provider_subject = :s").param("s", subject)
            .query(String.class).single()).isEqualTo(first);
    }

    @Test
    void aVerifiedEmailLinksAnExistingLocalAccountAndAnUnverifiedOneIsRefused() throws Exception {
        String email = "sso-link-" + ApiClient.uid() + "@conformance.example.com";
        assertThat(api.register(email, "Local").status()).isEqualTo(201);

        // Unverified at the IdP: refuse rather than take over the account
        Start unverified = start(null);
        IDP.token("{\"id_token\":\"" + idToken(KEYS, "sub-unverified-" + email, email, false, unverified.nonce()) + "\"}");
        MvcResult refused = callback("c1", unverified.state());
        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(refused.getResponse().getContentAsString()).contains("email_unverified");

        // Verified: the same row is linked, keeping its password hash and id
        Start verified = start(null);
        String subject = "sub-verified-" + email;
        IDP.token("{\"id_token\":\"" + idToken(KEYS, subject, email, true, verified.nonce()) + "\"}");
        assertThat(callback("c2", verified.state()).getResponse().getStatus()).isEqualTo(302);

        Map<String, Object> row = jdbc.sql("SELECT identity_provider, provider_subject, password_hash IS NOT NULL AS has_password "
                + "FROM users WHERE email = CAST(:e AS citext)").param("e", email).query().singleRow();
        assertThat(row).containsEntry("identity_provider", "keycloak").containsEntry("provider_subject", subject)
            .containsEntry("has_password", true);
    }

    @Test
    void theLoginStateIsSingleUse() throws Exception {
        String email = "sso-replay-" + ApiClient.uid() + "@conformance.example.com";
        Start started = start(null);
        IDP.token("{\"id_token\":\"" + idToken(KEYS, "sub-" + email, email, true, started.nonce()) + "\"}");
        assertThat(callback("c1", started.state()).getResponse().getStatus()).isEqualTo(302);

        MvcResult replay = callback("c1", started.state());
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(replay.getResponse().getContentAsString()).contains("Unknown or expired login state");
    }

    @Test
    void anUnknownStateAndAProviderErrorAreBoth401() throws Exception {
        MvcResult unknown = callback("c1", "never-issued");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);

        MvcResult refused = mvc.perform(get("/v1/auth/oidc/callback?error=access_denied")).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(refused.getResponse().getContentAsString()).contains("Identity provider refused the login");

        MvcResult missing = mvc.perform(get("/v1/auth/oidc/callback")).andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(missing.getResponse().getContentAsString()).contains("Missing code or state");
    }

    @Test
    void aTokenSignedByTheWrongKeyIsRejected() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("RSA").genKeyPair();
        Start started = start(null);
        IDP.token("{\"id_token\":\"" + idToken(attacker, "sub-evil", "evil@example.com", true, started.nonce()) + "\"}");
        MvcResult result = callback("c1", started.state());
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("id_token rejected");
    }

    @Test
    void aNonceFromAnotherLoginIsRejected() throws Exception {
        Start mine = start(null);
        Start other = start(null);
        IDP.token("{\"id_token\":\"" + idToken(KEYS, "sub-nonce", "nonce@example.com", true, other.nonce()) + "\"}");
        MvcResult result = callback("c1", mine.state());
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("nonce mismatch");
    }

    @Test
    void theMetaEndpointAdvertisesTheProviderSoTheConsoleShowsTheSsoButton() throws Exception {
        assertThat(api.get("/v1/meta", Map.of()).text("identity_provider")).isEqualTo("keycloak");
    }

    /** A Keycloak-shaped stub: the JWKS and one scripted token response. */
    static final class StubIdp implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        private volatile String certs = "{\"keys\":[]}";
        private volatile String token = "{}";

        StubIdp() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String body = exchange.getRequestURI().getPath().endsWith("/certs") ? certs : token;
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void certs(String body) {
            this.certs = body;
        }

        void token(String body) {
            this.token = body;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
