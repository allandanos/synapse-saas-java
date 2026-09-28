package dev.synapse.identity;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetTokenRepository {

    public record ResetToken(UUID id, UUID userId) {}

    private final JdbcClient jdbc;

    public PasswordResetTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID userId, String tokenHash, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO password_reset_tokens (id, user_id, token_hash, expires_at, max_uses)
                VALUES (:id, :userId, :tokenHash, :expiresAt, 1)
                """)
            .param("id", id).param("userId", userId).param("tokenHash", tokenHash).param("expiresAt", Rows.at(expiresAt))
            .update();
        return id;
    }

    /** Unused and not yet expired. */
    public Optional<ResetToken> findUsable(String tokenHash, Instant now) {
        return jdbc.sql("""
                SELECT id, user_id FROM password_reset_tokens
                WHERE token_hash = :hash AND used_at IS NULL AND expires_at >= :now
                """)
            .param("hash", tokenHash).param("now", Rows.at(now))
            .query((rs, i) -> new ResetToken(Rows.uuid(rs, "id"), Rows.uuid(rs, "user_id")))
            .optional();
    }

    public void markUsed(UUID id, Instant at) {
        jdbc.sql("UPDATE password_reset_tokens SET used_at = :at WHERE id = :id").param("at", Rows.at(at)).param("id", id).update();
    }
}
