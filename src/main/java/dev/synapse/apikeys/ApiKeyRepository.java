package dev.synapse.apikeys;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepository {

    private static final RowMapper<ApiKey> MAPPER = (rs, i) -> new ApiKey(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("name"), rs.getString("prefix"), rs.getString("key_hash"),
        Rows.strings(rs, "scopes"), Rows.instant(rs, "expires_at"), Rows.instant(rs, "last_used_at"), Rows.instant(rs, "revoked_at"),
        Rows.uuid(rs, "created_by_user_id"), Rows.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ApiKey insert(UUID organizationId, String name, String prefix, String keyHash, List<String> scopes, Instant expiresAt, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO api_keys (id, organization_id, name, prefix, key_hash, scopes, expires_at, created_by_user_id, metadata)
                VALUES (:id, :org, :name, :prefix, :hash, :scopes, :expiresAt, :createdBy, '{}'::jsonb)
                """)
            .param("id", id).param("org", organizationId).param("name", name).param("prefix", prefix).param("hash", keyHash)
            .param("scopes", scopes.toArray(String[]::new)).param("expiresAt", Rows.at(expiresAt)).param("createdBy", createdBy)
            .update();
        return findById(id).orElseThrow();
    }

    public List<ApiKey> listForOrganization(UUID organizationId) {
        return jdbc.sql("SELECT * FROM api_keys WHERE organization_id = :org ORDER BY created_at DESC, id")
            .param("org", organizationId).query(MAPPER).list();
    }

    public Optional<ApiKey> findById(UUID id) {
        return jdbc.sql("SELECT * FROM api_keys WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<ApiKey> findByHash(String keyHash) {
        return jdbc.sql("SELECT * FROM api_keys WHERE key_hash = :hash").param("hash", keyHash).query(MAPPER).optional();
    }

    public void revoke(UUID id, Instant at) {
        jdbc.sql("UPDATE api_keys SET revoked_at = :at, updated_at = now() WHERE id = :id").param("at", Rows.at(at)).param("id", id).update();
    }

    public void touchLastUsed(UUID id, Instant at) {
        jdbc.sql("UPDATE api_keys SET last_used_at = :at WHERE id = :id").param("at", Rows.at(at)).param("id", id).update();
    }
}
