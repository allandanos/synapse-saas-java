package dev.synapse.storage;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code stored_files}: org-scoped, soft-deleted, newest first. */
@Repository
public class StoredFileRepository {

    private static final RowMapper<StoredFile> MAPPER = (rs, i) -> new StoredFile(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("key"), rs.getString("name"),
        rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("status"), Rows.instant(rs, "deleted_at"),
        Rows.uuid(rs, "created_by_user_id"), Rows.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public StoredFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ready, live rows of one org, newest first. */
    public List<StoredFile> page(UUID organizationId, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM stored_files
                WHERE organization_id = :org AND deleted_at IS NULL AND status = 'ready'
                ORDER BY created_at DESC, id
                LIMIT :limit OFFSET :offset
                """)
            .param("org", organizationId).param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    public long count(UUID organizationId) {
        return jdbc.sql("SELECT count(*) FROM stored_files WHERE organization_id = :org AND deleted_at IS NULL AND status = 'ready'")
            .param("org", organizationId).query(Long.class).single();
    }

    /** Cross-tenant, deleted and wrong-status rows are all the same 404. */
    public Optional<StoredFile> findScoped(UUID id, UUID organizationId, List<String> statuses) {
        return jdbc.sql("""
                SELECT * FROM stored_files
                WHERE id = :id AND organization_id = :org AND deleted_at IS NULL AND status = ANY(:statuses)
                """)
            .param("id", id).param("org", organizationId).param("statuses", statuses.toArray(String[]::new))
            .query(MAPPER).optional();
    }

    public StoredFile insert(UUID organizationId, String key, String name, String contentType, long sizeBytes, String status,
                             UUID createdByUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO stored_files (id, organization_id, key, name, content_type, size_bytes, status, created_by_user_id)
                VALUES (:id, :org, :key, :name, :contentType, :size, :status, :createdBy)
                """)
            .param("id", id).param("org", organizationId).param("key", key).param("name", name)
            .param("contentType", contentType).param("size", sizeBytes).param("status", status).param("createdBy", createdByUserId)
            .update();
        return findScoped(id, organizationId, List.of(StoredFile.PENDING, StoredFile.READY)).orElseThrow();
    }

    public StoredFile markReady(UUID id, UUID organizationId) {
        jdbc.sql("UPDATE stored_files SET status = 'ready', updated_at = now() WHERE id = :id AND organization_id = :org")
            .param("id", id).param("org", organizationId).update();
        return findScoped(id, organizationId, List.of(StoredFile.READY)).orElseThrow();
    }

    public void softDelete(UUID id, UUID organizationId, Instant at) {
        jdbc.sql("UPDATE stored_files SET deleted_at = :at, updated_at = now() WHERE id = :id AND organization_id = :org")
            .param("id", id).param("org", organizationId).param("at", Rows.at(at)).update();
    }
}
