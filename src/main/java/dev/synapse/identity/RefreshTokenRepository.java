package dev.synapse.identity;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {

    private static final RowMapper<RefreshToken> MAPPER = (rs, i) -> new RefreshToken(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id"), rs.getString("token_hash"), Rows.uuid(rs, "organization_id"),
        Rows.instant(rs, "expires_at"), Rows.instant(rs, "revoked_at"), Rows.uuid(rs, "replaced_by_token_id"),
        rs.getString("user_agent"), rs.getString("ip"), Rows.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, String tokenHash, UUID organizationId, Instant expiresAt, String userAgent, String ip) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, organization_id, expires_at, user_agent, ip)
                VALUES (:id, :userId, :tokenHash, :organizationId, :expiresAt, :userAgent, :ip)
                """)
            .param("id", id).param("userId", userId).param("tokenHash", tokenHash).param("organizationId", organizationId)
            .param("expiresAt", Rows.at(expiresAt)).param("userAgent", truncate(userAgent, 500)).param("ip", truncate(ip, 45))
            .update();
        return id;
    }

    public Optional<RefreshToken> findByHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM refresh_tokens WHERE token_hash = :hash").param("hash", tokenHash).query(MAPPER).optional();
    }

    public void markRotated(UUID id, UUID replacedBy, Instant at) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :at, replaced_by_token_id = :replacedBy WHERE id = :id")
            .param("at", Rows.at(at)).param("replacedBy", replacedBy).param("id", id).update();
    }

    public void revoke(UUID id, Instant at) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
            .param("at", Rows.at(at)).param("id", id).update();
    }

    /** Kill every live session of a user (reuse detected, password reset). */
    public int revokeAllActiveForUser(UUID userId, Instant at) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = :at WHERE user_id = :userId AND revoked_at IS NULL")
            .param("at", Rows.at(at)).param("userId", userId).update();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
