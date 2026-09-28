package dev.synapse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import dev.synapse.core.errors.AuthenticationError;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtCodecTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes-long!!";
    private final JwtCodec codec = new JwtCodec(SECRET, 900);

    @Test
    void accessTokenCarriesTheReferenceClaims() {
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        String token = codec.createAccessToken(user, "a@example.com", org, true);

        DecodedJWT raw = JWT.decode(token);
        assertThat(raw.getAlgorithm()).isEqualTo("HS256");
        assertThat(raw.getSubject()).isEqualTo(user.toString());
        assertThat(raw.getIssuer()).isEqualTo("synapse-saas");
        assertThat(raw.getClaim("type").asString()).isEqualTo("access");
        assertThat(raw.getClaim("email").asString()).isEqualTo("a@example.com");
        assertThat(raw.getClaim("org").asString()).isEqualTo(org.toString());
        assertThat(raw.getClaim("platform_admin").asBoolean()).isTrue();
        assertThat(raw.getExpiresAtAsInstant().getEpochSecond() - raw.getIssuedAtAsInstant().getEpochSecond()).isEqualTo(900);

        JwtCodec.AccessClaims claims = codec.decode(token);
        assertThat(claims.userId()).isEqualTo(user);
        assertThat(claims.organizationId()).isEqualTo(org);
        assertThat(claims.platformAdmin()).isTrue();
    }

    @Test
    void orgAndPlatformAdminClaimsAreOmittedWhenNotSet() {
        DecodedJWT raw = JWT.decode(codec.createAccessToken(UUID.randomUUID(), "a@example.com", null, false));
        assertThat(raw.getClaim("org").isMissing()).isTrue();
        assertThat(raw.getClaim("platform_admin").isMissing()).isTrue();
    }

    @Test
    void expiredWrongSecretAndWrongTypeAreTheSameOpaque401() {
        String expired = codec.createAccessToken(UUID.randomUUID(), "a@example.com", null, false, -60);
        assertThatThrownBy(() -> codec.decode(expired)).isInstanceOf(AuthenticationError.class)
            .hasMessage("Access token is invalid or expired");

        String other = new JwtCodec("another-secret-key-with-32-bytes-minimum!!", 900).createAccessToken(UUID.randomUUID(), "a@example.com", null, false);
        assertThatThrownBy(() -> codec.decode(other)).isInstanceOf(AuthenticationError.class)
            .hasMessage("Access token is invalid or expired");

        String refreshTyped = JWT.create().withSubject(UUID.randomUUID().toString()).withIssuer("synapse-saas")
            .withIssuedAt(Instant.now()).withExpiresAt(Instant.now().plusSeconds(60)).withClaim("type", "refresh")
            .sign(Algorithm.HMAC256(SECRET));
        assertThatThrownBy(() -> codec.decode(refreshTyped)).isInstanceOf(AuthenticationError.class);

        assertThatThrownBy(() -> codec.decode("garbage")).isInstanceOf(AuthenticationError.class);
    }

    @Test
    void nonUuidSubjectIsRejected() {
        String token = JWT.create().withSubject("not-a-uuid").withIssuer("synapse-saas").withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(60)).withClaim("type", "access").sign(Algorithm.HMAC256(SECRET));
        assertThatThrownBy(() -> codec.decode(token)).isInstanceOf(AuthenticationError.class).hasMessage("Invalid token subject");
    }
}
