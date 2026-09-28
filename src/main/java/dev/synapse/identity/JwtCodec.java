package dev.synapse.identity;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.AuthenticationError;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HS256 access tokens with the reference's claims: {@code sub}, {@code email},
 * {@code iat}, {@code exp}, {@code iss=synapse-saas}, {@code type=access},
 * optional {@code org} and {@code platform_admin}. The algorithm is pinned;
 * every failure is the same opaque 401.
 */
@Component
public class JwtCodec {

    public static final String ISSUER = "synapse-saas";
    public static final String TYPE_ACCESS = "access";

    public record AccessClaims(UUID userId, String email, UUID organizationId, boolean platformAdmin, Instant expiresAt) {}

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final int ttlSeconds;

    @Autowired
    public JwtCodec(SynapseProperties props) {
        this(props.secretKey(), props.accessTokenTtlSeconds());
    }

    public JwtCodec(String secret, int ttlSeconds) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.ttlSeconds = ttlSeconds;
        this.verifier = JWT.require(algorithm)
            .withIssuer(ISSUER)
            .withClaimPresence("exp")
            .withClaimPresence("iat")
            .withClaimPresence("sub")
            .withClaimPresence("type")
            .build();
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }

    public String createAccessToken(UUID userId, String email, UUID organizationId, boolean platformAdmin) {
        return createAccessToken(userId, email, organizationId, platformAdmin, ttlSeconds);
    }

    public String createAccessToken(UUID userId, String email, UUID organizationId, boolean platformAdmin, int ttl) {
        Instant now = Instant.now();
        JWTCreator.Builder builder = JWT.create()
            .withSubject(userId.toString())
            .withClaim("email", email)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(ttl))
            .withIssuer(ISSUER)
            .withClaim("type", TYPE_ACCESS);
        if (organizationId != null) {
            builder.withClaim("org", organizationId.toString());
        }
        if (platformAdmin) {
            builder.withClaim("platform_admin", true);
        }
        return builder.sign(algorithm);
    }

    /** Decode + validate. Throws {@link AuthenticationError} on any failure (never says which check failed). */
    public AccessClaims decode(String token) {
        DecodedJWT jwt;
        try {
            jwt = verifier.verify(token);
        } catch (JWTVerificationException e) {
            throw new AuthenticationError("Access token is invalid or expired");
        }
        if (!TYPE_ACCESS.equals(jwt.getClaim("type").asString())) {
            throw new AuthenticationError("Access token is invalid or expired");
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException e) {
            throw new AuthenticationError("Invalid token subject");
        }
        UUID org = null;
        String orgClaim = jwt.getClaim("org").asString();
        if (orgClaim != null && !orgClaim.isBlank()) {
            try {
                org = UUID.fromString(orgClaim);
            } catch (IllegalArgumentException ignored) {
                org = null;
            }
        }
        Boolean admin = jwt.getClaim("platform_admin").asBoolean();
        return new AccessClaims(userId, jwt.getClaim("email").asString(), org, Boolean.TRUE.equals(admin),
            jwt.getExpiresAtAsInstant());
    }
}
