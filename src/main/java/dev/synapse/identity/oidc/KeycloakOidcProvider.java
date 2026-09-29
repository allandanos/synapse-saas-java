package dev.synapse.identity.oidc;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.AuthenticationError;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keycloak adapter — enabled via {@code SYNAPSE_IDENTITY_PROVIDER=keycloak}
 * (ADR 0010; reference: {@code identity/provider.py:KeycloakOIDCProvider}).
 *
 * <p>Browser logins use the authorization-code flow with PKCE
 * ({@code /v1/auth/oidc/start} → Keycloak → {@code /v1/auth/oidc/callback}).
 * The id_token is verified against the realm JWKS for signature, issuer,
 * audience, expiry and the nonce bound to this login attempt.
 */
@Component
public class KeycloakOidcProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOidcProvider.class);
    public static final String NAME = "keycloak";
    public static final String SCOPE = "openid email profile";
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final SynapseProperties props;
    private final JwksCache jwks;
    private final ObjectMapper json;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public KeycloakOidcProvider(SynapseProperties props, JwksCache jwks, ObjectMapper json) {
        this(props, jwks, json, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public KeycloakOidcProvider(SynapseProperties props, JwksCache jwks, ObjectMapper json, HttpClient http) {
        this.props = props;
        this.jwks = jwks;
        this.json = json;
        this.http = http;
    }

    public String issuer() {
        requireConfigured();
        return props.keycloakBaseUrl().replaceAll("/+$", "") + "/realms/" + props.keycloakRealm();
    }

    private void requireConfigured() {
        if (props.keycloakBaseUrl().isEmpty() || props.keycloakRealm().isEmpty()) {
            throw new AuthenticationError("Keycloak is not configured");
        }
    }

    // ── Authorization-code flow ──────────────────────────────────────────────────

    /** Where to send the browser to start an OIDC login. */
    public String authorizationUrl(String redirectUri, String state, String nonce, String codeChallenge) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", props.keycloakClientId());
        query.put("response_type", "code");
        query.put("scope", SCOPE);
        query.put("redirect_uri", redirectUri);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        return issuer() + "/protocol/openid-connect/auth?" + encode(query);
    }

    /**
     * Authorization-code callback → the verified id_token claims.
     *
     * <p>Verifies the RS256 signature against the realm JWKS (cached one hour,
     * refetched once on an unknown kid), {@code iss}, {@code aud}, expiry and
     * the {@code nonce} bound to this login attempt.
     */
    public Map<String, Object> exchangeCode(String code, String redirectUri, String codeVerifier, String nonce) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", props.keycloakClientId());
        form.put("client_secret", props.keycloakClientSecret());
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        if (codeVerifier != null && !codeVerifier.isEmpty()) {
            form.put("code_verifier", codeVerifier);
        }
        Map<String, Object> tokens = token(form, "OIDC code exchange failed");
        String idToken = tokens.get("id_token") == null ? "" : String.valueOf(tokens.get("id_token"));
        if (idToken.isEmpty()) {
            throw new AuthenticationError("OIDC token response carried no id_token");
        }
        return verify(idToken, nonce);
    }

    /** Verify an id_token and return its claims as a plain map. */
    public Map<String, Object> verify(String idToken, String nonce) {
        DecodedJWT decoded;
        try {
            decoded = JWT.decode(idToken);
        } catch (RuntimeException e) {
            throw new AuthenticationError("OIDC id_token rejected: " + e.getMessage());
        }
        RSAPublicKey key = jwks.signingKey(issuer(), issuer() + "/protocol/openid-connect/certs", decoded.getKeyId());
        if (key == null) {
            throw new AuthenticationError("No matching Keycloak signing key for token");
        }
        try {
            JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(issuer())
                .withAudience(props.keycloakClientId())
                .withClaimPresence("exp")
                .withClaimPresence("iat")
                .withClaimPresence("sub")
                .build()
                .verify(idToken);
        } catch (JWTVerificationException e) {
            log.info("oidc_id_token_rejected error={}", e.getMessage());
            throw new AuthenticationError("OIDC id_token rejected: " + e.getMessage());
        }
        Map<String, Object> claims = claims(decoded);
        if (nonce != null && !nonce.equals(claims.get("nonce"))) {
            throw new AuthenticationError("OIDC nonce mismatch");
        }
        return claims;
    }

    /**
     * Email+password proxied to Keycloak (the resource-owner password grant).
     * Off unless {@code SYNAPSE_KEYCLOAK_ALLOW_PASSWORD_GRANT=true} — the code
     * flow is the default.
     */
    public Map<String, Object> verifyCredentials(String email, String password) {
        if (!props.keycloakAllowPasswordGrant()) {
            return null;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", props.keycloakClientId());
        form.put("client_secret", props.keycloakClientSecret());
        form.put("username", email);
        form.put("password", password);
        form.put("scope", "openid");
        Map<String, Object> tokens;
        try {
            tokens = token(form, "OIDC password grant failed");
        } catch (AuthenticationError e) {
            return null; // bad credentials are not an error here, they are a "no"
        }
        String idToken = tokens.get("id_token") == null ? "" : String.valueOf(tokens.get("id_token"));
        if (idToken.isEmpty()) {
            return null;
        }
        try {
            return verify(idToken, null);
        } catch (AuthenticationError e) {
            return null;
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> token(Map<String, String> form, String failure) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AuthenticationError(failure + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthenticationError(failure + ": interrupted");
        }
        if (response.statusCode() != 200) {
            log.info("oidc_code_exchange_failed status={}", response.statusCode());
            throw new AuthenticationError(failure);
        }
        try {
            return json.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new AuthenticationError(failure + ": non-JSON token response");
        }
    }

    private static Map<String, Object> claims(DecodedJWT decoded) {
        Map<String, Object> claims = new LinkedHashMap<>();
        decoded.getClaims().forEach((name, claim) -> claims.put(name, claim.as(Object.class)));
        return claims;
    }

    private static String encode(Map<String, String> values) {
        StringBuilder query = new StringBuilder();
        values.forEach((key, value) -> {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return query.toString();
    }
}
