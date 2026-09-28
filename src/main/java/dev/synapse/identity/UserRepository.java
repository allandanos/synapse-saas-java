package dev.synapse.identity;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    static final RowMapper<User> MAPPER = (rs, i) -> new User(
        Rows.uuid(rs, "id"), rs.getString("email"), rs.getString("password_hash"), rs.getString("display_name"),
        rs.getString("avatar_url"), rs.getBoolean("is_platform_admin"), rs.getBoolean("is_active"),
        Rows.instant(rs, "last_login_at"), rs.getString("identity_provider"), rs.getString("provider_subject"),
        Rows.local(rs, "created_at"));

    private static final String COLUMNS = """
        id, email, password_hash, display_name, avatar_url, is_platform_admin, is_active, last_login_at,
        identity_provider, provider_subject, created_at
        """;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code email} is citext: the comparison is case-insensitive like the reference. */
    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = CAST(:email AS citext)")
            .param("email", email).query(MAPPER).optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public User insert(String email, String passwordHash, String displayName, boolean platformAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO users (id, email, password_hash, display_name, is_platform_admin, is_active, identity_provider)
                VALUES (:id, CAST(:email AS citext), :passwordHash, :displayName, :platformAdmin, true, 'local')
                """)
            .param("id", id).param("email", email).param("passwordHash", passwordHash)
            .param("displayName", displayName).param("platformAdmin", platformAdmin)
            .update();
        return findById(id).orElseThrow();
    }

    public void touchLastLogin(UUID id, Instant at) {
        jdbc.sql("UPDATE users SET last_login_at = :at, updated_at = now() WHERE id = :id")
            .param("at", Rows.at(at)).param("id", id).update();
    }

    public void updatePasswordHash(UUID id, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = :hash, updated_at = now() WHERE id = :id")
            .param("hash", passwordHash).param("id", id).update();
    }

    public void setPlatformAdmin(UUID id, boolean platformAdmin) {
        jdbc.sql("UPDATE users SET is_platform_admin = :flag, updated_at = now() WHERE id = :id")
            .param("flag", platformAdmin).param("id", id).update();
    }
}
