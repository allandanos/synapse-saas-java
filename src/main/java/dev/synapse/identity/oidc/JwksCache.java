package dev.synapse.identity.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The realm's JWKS, cached one hour per issuer and refetched once on an unknown
 * {@code kid} (reference: {@code identity/provider.py:_jwks}, {@code _signing_key}).
 *
 * <p>Keycloak rotates signing keys; a token minted moments after a rotation
 * carries a {@code kid} the cache has never seen, so one forced refetch is the
 * difference between a working login and a mystery 401.
 */
@Component
public class JwksCache {

    public static final Duration TTL = Duration.ofHours(1);
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private record Entry(long fetchedAtNanos, Map<String, RSAPublicKey> keys) {}

    private final ObjectMapper json;
    private final HttpClient http;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public JwksCache(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public JwksCache(ObjectMapper json, HttpClient http) {
        this.json = json;
        this.http = http;
    }

    /** The RSA key for {@code kid} at {@code issuer}, or null after a forced refetch found none. */
    public RSAPublicKey signingKey(String issuer, String jwksUri, String kid) {
        RSAPublicKey cached = keys(issuer, jwksUri, false).get(kid);
        if (cached != null) {
            return cached;
        }
        return keys(issuer, jwksUri, true).get(kid);
    }

    public void clear() {
        cache.clear();
    }

    private Map<String, RSAPublicKey> keys(String issuer, String jwksUri, boolean force) {
        Entry entry = cache.get(issuer);
        if (!force && entry != null && System.nanoTime() - entry.fetchedAtNanos() < TTL.toNanos()) {
            return entry.keys();
        }
        Map<String, RSAPublicKey> fetched = fetch(jwksUri);
        cache.put(issuer, new Entry(System.nanoTime(), fetched));
        return fetched;
    }

    @SuppressWarnings("unchecked")
    private Map<String, RSAPublicKey> fetch(String jwksUri) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUri)).timeout(TIMEOUT).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("JWKS fetch failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("JWKS fetch was interrupted", e);
        }
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("JWKS endpoint answered " + response.statusCode());
        }
        Map<String, Object> body;
        try {
            body = json.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("JWKS endpoint returned a non-JSON response", e);
        }
        Map<String, RSAPublicKey> keys = new ConcurrentHashMap<>();
        Object raw = body.get("keys");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> jwk = (Map<String, Object>) item;
                if (!"RSA".equals(jwk.get("kty")) || jwk.get("kid") == null) {
                    continue;
                }
                keys.put(String.valueOf(jwk.get("kid")), rsaKey(jwk));
            }
        }
        return keys;
    }

    private static RSAPublicKey rsaKey(Map<String, Object> jwk) {
        try {
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(String.valueOf(jwk.get("n"))));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(String.valueOf(jwk.get("e"))));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception e) {
            throw new IllegalStateException("JWKS carried an unusable RSA key: " + e, e);
        }
    }
}
