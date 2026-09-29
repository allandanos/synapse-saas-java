package dev.synapse.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.support.StubProviderServer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * id_token verification against a stub realm (reference:
 * {@code tests/unit/identity/test_keycloak_provider.py}): signature, issuer,
 * audience, expiry, nonce, and one forced JWKS refetch on an unknown kid.
 */
class KeycloakOidcProviderTest {

    private static final String REALM = "synapse";
    private static final String CLIENT_ID = "synapse-web";
    private static final String CERTS_PATH = "/realms/" + REALM + "/protocol/openid-connect/certs";
    private static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";

    private StubProviderServer keycloak;
    private KeycloakOidcProvider provider;
    private JwksCache jwks;
    private KeyPair keyPair;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        keycloak = new StubProviderServer();
        keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair();
        keycloak.on("GET", CERTS_PATH, jwksJson("key-1", (RSAPublicKey) keyPair.getPublic()));
        SynapseProperties props = mock(SynapseProperties.class);
        when(props.keycloakBaseUrl()).thenReturn(keycloak.baseUrl());
        when(props.keycloakRealm()).thenReturn(REALM);
        when(props.keycloakClientId()).thenReturn(CLIENT_ID);
        when(props.keycloakClientSecret()).thenReturn("dev-client-secret");
        jwks = new JwksCache(json);
        provider = new KeycloakOidcProvider(props, jwks, json);
    }

    @AfterEach
    void tearDown() {
        keycloak.close();
    }

    private String issuer() {
        return keycloak.baseUrl() + "/realms/" + REALM;
    }

    private static String jwksJson(String kid, RSAPublicKey key) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + kid + "\",\"n\":\""
            + encoder.encodeToString(key.getModulus().toByteArray()) + "\",\"e\":\""
            + encoder.encodeToString(key.getPublicExponent().toByteArray()) + "\"}]}";
    }

    private String token(String kid, RSAPrivateKey signingKey, String issuer, String audience, Instant expiry, String nonce) {
        return JWT.create()
            .withKeyId(kid)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject("sub-123")
            .withIssuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
            .withExpiresAt(Date.from(expiry))
            .withClaim("email", "sso@acme.example.com")
            .withClaim("email_verified", true)
            .withClaim("nonce", nonce)
            .sign(Algorithm.RSA256((RSAPublicKey) keyPair.getPublic(), signingKey));
    }

    private String goodToken(String nonce) {
        return token("key-1", (RSAPrivateKey) keyPair.getPrivate(), issuer(), CLIENT_ID,
            Instant.now().plus(5, ChronoUnit.MINUTES), nonce);
    }

    @Test
    void aValidTokenYieldsItsClaims() {
        Map<String, Object> claims = provider.verify(goodToken("n1"), "n1");
        assertThat(claims).containsEntry("sub", "sub-123").containsEntry("email", "sso@acme.example.com");
        assertThat(claims.get("email_verified")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void aWrongIssuerIsRejected() {
        String bad = token("key-1", (RSAPrivateKey) keyPair.getPrivate(), "https://evil.example.com/realms/synapse",
            CLIENT_ID, Instant.now().plus(5, ChronoUnit.MINUTES), "n1");
        assertThatThrownBy(() -> provider.verify(bad, "n1"))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("id_token rejected");
    }

    @Test
    void aWrongAudienceIsRejected() {
        String bad = token("key-1", (RSAPrivateKey) keyPair.getPrivate(), issuer(), "someone-else",
            Instant.now().plus(5, ChronoUnit.MINUTES), "n1");
        assertThatThrownBy(() -> provider.verify(bad, "n1"))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("id_token rejected");
    }

    @Test
    void anExpiredTokenIsRejected() {
        String bad = token("key-1", (RSAPrivateKey) keyPair.getPrivate(), issuer(), CLIENT_ID,
            Instant.now().minus(1, ChronoUnit.MINUTES), "n1");
        assertThatThrownBy(() -> provider.verify(bad, "n1"))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("id_token rejected");
    }

    @Test
    void aForeignSignatureIsRejected() throws Exception {
        KeyPair attacker = KeyPairGenerator.getInstance("RSA").genKeyPair();
        String bad = JWT.create().withKeyId("key-1").withIssuer(issuer()).withAudience(CLIENT_ID)
            .withSubject("sub-123").withIssuedAt(new Date())
            .withExpiresAt(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
            .sign(Algorithm.RSA256((RSAPublicKey) attacker.getPublic(), (RSAPrivateKey) attacker.getPrivate()));
        assertThatThrownBy(() -> provider.verify(bad, null))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("id_token rejected");
    }

    @Test
    void aNonceMismatchIsRejected() {
        assertThatThrownBy(() -> provider.verify(goodToken("n1"), "n2"))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("nonce mismatch");
    }

    @Test
    void anUnknownKidTriggersOneRefetch() throws Exception {
        provider.verify(goodToken("n1"), "n1"); // warms the cache with key-1
        KeyPair rotated = KeyPairGenerator.getInstance("RSA").genKeyPair();
        keycloak.on("GET", CERTS_PATH, jwksJson("key-2", (RSAPublicKey) rotated.getPublic()));
        String fresh = JWT.create().withKeyId("key-2").withIssuer(issuer()).withAudience(CLIENT_ID)
            .withSubject("sub-456").withIssuedAt(new Date())
            .withExpiresAt(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
            .sign(Algorithm.RSA256((RSAPublicKey) rotated.getPublic(), (RSAPrivateKey) rotated.getPrivate()));
        assertThat(provider.verify(fresh, null)).containsEntry("sub", "sub-456");
    }

    @Test
    void aKidThatSurvivesTheRefetchIsRejected() {
        String bad = token("key-unknown", (RSAPrivateKey) keyPair.getPrivate(), issuer(), CLIENT_ID,
            Instant.now().plus(5, ChronoUnit.MINUTES), null);
        assertThatThrownBy(() -> provider.verify(bad, null))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("No matching Keycloak signing key");
    }

    @Test
    void theAuthorizationUrlCarriesPkceAndTheNonce() {
        String url = provider.authorizationUrl("http://localhost:8080/v1/auth/oidc/callback", "st", "no", "ch");
        assertThat(url).startsWith(issuer() + "/protocol/openid-connect/auth?");
        assertThat(url).contains("client_id=" + CLIENT_ID)
            .contains("response_type=code")
            .contains("scope=openid+email+profile")
            .contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fv1%2Fauth%2Foidc%2Fcallback")
            .contains("state=st").contains("nonce=no")
            .contains("code_challenge=ch").contains("code_challenge_method=S256");
    }

    @Test
    void aCodeExchangeWithoutAnIdTokenFails() {
        keycloak.on("POST", TOKEN_PATH, "{\"access_token\":\"a\"}");
        assertThatThrownBy(() -> provider.exchangeCode("code", "http://localhost/cb", "verifier", "n1"))
            .isInstanceOf(AuthenticationError.class).hasMessageContaining("no id_token");
    }

    @Test
    void aCodeExchangeReturnsTheVerifiedClaims() {
        keycloak.on("POST", TOKEN_PATH, "{\"id_token\":\"" + goodToken("n1") + "\"}");
        Map<String, Object> claims = provider.exchangeCode("code", "http://localhost/cb", "verifier", "n1");
        assertThat(claims).containsEntry("sub", "sub-123");
        StubProviderServer.Call exchange = keycloak.calls().stream()
            .filter(c -> TOKEN_PATH.equals(c.path())).findFirst().orElseThrow();
        assertThat(exchange.body()).contains("grant_type=authorization_code").contains("code_verifier=verifier")
            .contains("client_secret=dev-client-secret");
    }

    @Test
    void theSafeReturnToRejectsOffSiteTargets() {
        assertThat(OidcController.safeReturnTo("/settings")).isEqualTo("/settings");
        assertThat(OidcController.safeReturnTo("//evil.example.com")).isEqualTo("/dashboard");
        assertThat(OidcController.safeReturnTo("https://evil.example.com")).isEqualTo("/dashboard");
        assertThat(OidcController.safeReturnTo(null)).isEqualTo("/dashboard");
    }

    @Test
    void thePkceChallengeIsTheUrlSafeSha256OfTheVerifier() {
        // RFC 7636 appendix B's worked example
        assertThat(OidcController.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
