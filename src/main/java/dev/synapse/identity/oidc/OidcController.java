package dev.synapse.identity.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.cache.Caches;
import dev.synapse.core.cache.VersionedCache;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.identity.IdentityService;
import dev.synapse.identity.RefreshCookie;
import dev.synapse.identity.User;
import dev.synapse.identity.dto.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * OIDC login — the authorization-code flow with PKCE (ADR 0010; reference:
 * {@code identity/router.py:oidc_start}, {@code oidc_callback}).
 *
 * <p>The browser never sees tokens in a URL: the callback sets the refresh
 * cookie and bounces to the console, which mints an access token through
 * {@code /v1/auth/refresh}.
 */
@RestController
@RequestMapping("/v1/auth/oidc")
public class OidcController {

    public static final String CALLBACK_PATH = "/v1/auth/oidc/callback";
    public static final String DEFAULT_RETURN_TO = "/dashboard";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeycloakOidcProvider provider;
    private final IdentityService identity;
    private final RefreshCookie cookie;
    private final SynapseProperties props;
    private final VersionedCache stateCache;
    private final ObjectMapper json;

    public OidcController(KeycloakOidcProvider provider, IdentityService identity, RefreshCookie cookie,
                          SynapseProperties props, Caches caches, ObjectMapper json) {
        this.provider = provider;
        this.identity = identity;
        this.cookie = cookie;
        this.props = props;
        this.stateCache = caches.oidcState();
        this.json = json;
    }

    /**
     * Start an SSO login: the PKCE verifier and nonce are kept server-side under
     * an opaque {@code state} for the cache's TTL; the browser goes to the IdP.
     */
    @GetMapping("/start")
    public ResponseEntity<Void> start(HttpServletRequest request,
                                      @RequestParam(name = "return_to", required = false) String returnTo) {
        requireKeycloak();
        String state = token(32);
        String verifier = token(64);
        String nonce = token(16);
        String challenge = challenge(verifier);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("verifier", verifier);
        pending.put("nonce", nonce);
        pending.put("return_to", safeReturnTo(returnTo));
        stateCache.set(state, write(pending));
        String url = provider.authorizationUrl(callbackUri(request), state, nonce, challenge);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /**
     * Finish an SSO login: consume the state (one shot), exchange the code with
     * PKCE, verify the id_token, link or create the user, set the refresh cookie.
     */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(HttpServletRequest request, HttpServletResponse response,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        requireKeycloak();
        if (error != null && !error.isEmpty()) {
            throw new AuthenticationError("Identity provider refused the login: " + error);
        }
        if (code == null || code.isEmpty() || state == null || state.isEmpty()) {
            throw new AuthenticationError("Missing code or state");
        }
        String raw = stateCache.get(state);
        if (raw == null) {
            throw new AuthenticationError("Unknown or expired login state");
        }
        stateCache.delete(state); // single use
        Map<String, Object> pending = read(raw);

        Map<String, Object> claims = provider.exchangeCode(code, callbackUri(request),
            String.valueOf(pending.get("verifier")), String.valueOf(pending.get("nonce")));
        User user = identity.linkOrCreateOidcUser(claims, KeycloakOidcProvider.NAME);
        TokenPair tokens = identity.issueTokensFor(user, request.getHeader("User-Agent"), request.getRemoteAddr());
        cookie.set(response, tokens.refreshToken());

        String returnTo = String.valueOf(pending.get("return_to"));
        String target = props.webOrigin().replaceAll("/+$", "") + "/auth/callback?return_to=" + encodePath(returnTo);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private void requireKeycloak() {
        if (!props.keycloakIdentity()) {
            throw new AuthenticationError("SSO is not available with the local identity provider");
        }
    }

    /** Only same-origin paths — never an open redirect. */
    static String safeReturnTo(String raw) {
        if (raw != null && raw.startsWith("/") && !raw.startsWith("//")) {
            return raw;
        }
        return DEFAULT_RETURN_TO;
    }

    private String callbackUri(HttpServletRequest request) {
        if (props.oidcRedirectUri() != null && !props.oidcRedirectUri().isEmpty()) {
            return props.oidcRedirectUri();
        }
        return ServletUriComponentsBuilder.fromContextPath(request).path(CALLBACK_PATH).toUriString();
    }

    /** {@code quote(value, safe="/")}: slashes stay readable in the console's URL. */
    static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20");
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static String token(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("OIDC login state is not serialisable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String raw) {
        try {
            return json.readValue(raw, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AuthenticationError("Unknown or expired login state");
        }
    }
}
