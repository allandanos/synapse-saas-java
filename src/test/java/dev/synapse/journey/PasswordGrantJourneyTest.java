package dev.synapse.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.synapse.identity.oidc.JwksCache;
import dev.synapse.support.ApiClient;
import dev.synapse.support.ApiClient.Res;
import dev.synapse.support.PostgresTestSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
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

/**
 * {@code SYNAPSE_KEYCLOAK_ALLOW_PASSWORD_GRANT=true}: the password form proxies
 * unknown or SSO-only accounts to Keycloak's resource-owner password grant
 * (reference: {@code tests/integration/test_oidc_login.py::TestPasswordGrantOptIn},
 * adopted in `synapse-saas@b581b33` — the setting used to be dead in both
 * implementations).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordGrantJourneyTest extends PostgresTestSupport {

    static final String REALM = "synapse";
    static final String CLIENT_ID = "synapse-web";
    static final StubIdp IDP;
    static final KeyPair KEYS;

    static {
        try {
            KEYS = KeyPairGenerator.getInstance("RSA").genKeyPair();
            IDP = new StubIdp();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("synapse.identity-provider", () -> "keycloak");
        registry.add("synapse.keycloak-allow-password-grant", () -> true);
        registry.add("synapse.keycloak-base-url", IDP::baseUrl);
        registry.add("synapse.keycloak-realm", () -> REALM);
        registry.add("synapse.keycloak-client-id", () -> CLIENT_ID);
        registry.add("synapse.keycloak-client-secret", () -> "dev-client-secret");
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
        IDP.certs(jwksJson((RSAPublicKey) KEYS.getPublic()));
        IDP.accept();
    }

    private static String jwksJson(RSAPublicKey key) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"key-1\",\"n\":\""
            + encoder.encodeToString(key.getModulus().toByteArray()) + "\",\"e\":\""
            + encoder.encodeToString(key.getPublicExponent().toByteArray()) + "\"}]}";
    }

    private String idToken(String subject, String email) {
        return JWT.create().withKeyId("key-1")
            .withIssuer(IDP.baseUrl() + "/realms/" + REALM)
            .withAudience(CLIENT_ID)
            .withSubject(subject)
            .withIssuedAt(new Date())
            .withExpiresAt(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
            .withClaim("email", email)
            .withClaim("email_verified", true)
            .withClaim("name", "Ropc " + subject)
            .sign(Algorithm.RSA256((RSAPublicKey) KEYS.getPublic(), (RSAPrivateKey) KEYS.getPrivate()));
    }

    private Res login(String email, String password) throws Exception {
        return api.post("/v1/auth/login", Map.of(), Map.of("email", email, "password", password));
    }

    @Test
    void anUnknownAccountIsCreatedFromTheGrantAndSignedIn() throws Exception {
        String email = "ropc-new-" + ApiClient.uid() + "@conformance.example.com";
        IDP.token("{\"id_token\":\"" + idToken("kc-" + email, email) + "\"}");

        Res ok = login(email, "kc-password");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().at("/user/email").asText()).isEqualTo(email);
        assertThat(ok.body().at("/tokens/access_token").asText()).isNotBlank();

        // Linked to the provider, and SSO-only: the grant never sets a local password
        Map<String, Object> row = jdbc.sql("SELECT identity_provider, password_hash IS NULL AS sso_only "
                + "FROM users WHERE email = CAST(:e AS citext)").param("e", email).query().singleRow();
        assertThat(row).containsEntry("identity_provider", "keycloak").containsEntry("sso_only", true);

        // The form posted the password grant with the client's credentials
        assertThat(IDP.lastTokenBody()).contains("grant_type=password")
            .contains("client_id=" + CLIENT_ID).contains("client_secret=dev-client-secret");
    }

    @Test
    void anExistingSsoOnlyAccountSignsInThroughTheGrant() throws Exception {
        String email = "ropc-known-" + ApiClient.uid() + "@conformance.example.com";
        IDP.token("{\"id_token\":\"" + idToken("kc-stable-" + email, email) + "\"}");
        String firstId = login(email, "kc-password").body().at("/user/id").asText();

        Res again = login(email, "kc-password");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().at("/user/id").asText()).isEqualTo(firstId); // same row, not a second account
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = CAST(:e AS citext)")
            .param("e", email).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aWrongPasswordIsAnOrdinaryInvalidCredentials() throws Exception {
        String email = "ropc-bad-" + ApiClient.uid() + "@conformance.example.com";
        IDP.reject(); // Keycloak answers 401 to the password grant

        Res bad = login(email, "not-the-password");
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.body().get("title").asText()).isEqualTo("invalid credentials");
        // Nothing leaks about whether the account exists at the provider
        assertThat(bad.body().toString()).doesNotContain("sso_url");
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = CAST(:e AS citext)")
            .param("e", email).query(Long.class).single()).isZero();
    }

    @Test
    void aLocalAccountWithAPasswordStillUsesTheLocalPath() throws Exception {
        String email = "ropc-local-" + ApiClient.uid() + "@conformance.example.com";
        assertThat(api.register(email, "Local").status()).isEqualTo(201);
        IDP.reject(); // the provider is never consulted for this one

        assertThat(login(email, ApiClient.PASSWORD).status()).isEqualTo(200);
        assertThat(login(email, "wrong-password-12345").status()).isEqualTo(401);
    }

    /** A Keycloak-shaped stub: the JWKS, one scripted token response, and a rejection mode. */
    static final class StubIdp {

        private final HttpServer server;
        private volatile String certs = "{\"keys\":[]}";
        private volatile String token = "{}";
        private volatile int tokenStatus = 200;
        private volatile String lastTokenBody = "";

        StubIdp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                boolean certsRequest = exchange.getRequestURI().getPath().endsWith("/certs");
                if (!certsRequest) {
                    lastTokenBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                }
                String body = certsRequest ? certs : token;
                int status = certsRequest ? 200 : tokenStatus;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String lastTokenBody() {
            return lastTokenBody;
        }

        void certs(String body) {
            this.certs = body;
        }

        void token(String body) {
            this.token = body;
        }

        void accept() {
            this.tokenStatus = 200;
        }

        void reject() {
            this.tokenStatus = 401;
            this.token = "{\"error\":\"invalid_grant\"}";
        }
    }
}
