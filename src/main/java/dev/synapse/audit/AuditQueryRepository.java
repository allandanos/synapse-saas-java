package dev.synapse.audit;

import dev.synapse.core.db.Json;
import dev.synapse.core.db.Rows;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read side of {@code audit_logs} — the rows {@code AuditService} has been
 * writing since milestone 2. Newest first, org-scoped, optionally filtered.
 */
@Repository
public class AuditQueryRepository {

    private static final String COLUMNS = """
        id, organization_id, actor_user_id, actor_type, event_type, target_type, target_id, diff::text AS diff, request_id, created_at
        """;

    private final JdbcClient jdbc;
    private final RowMapper<AuditEntry> mapper;

    public AuditQueryRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.mapper = (rs, i) -> new AuditEntry(Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), Rows.uuid(rs, "actor_user_id"),
            rs.getString("actor_type"), rs.getString("event_type"), rs.getString("target_type"), Rows.uuid(rs, "target_id"),
            rs.getString("diff") == null ? null : json.readMap(rs.getString("diff")), rs.getString("request_id"),
            Rows.instant(rs, "created_at"));
    }

    public List<AuditEntry> page(UUID organizationId, String eventType, UUID actorUserId, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM audit_logs
                 WHERE organization_id = :org
                   AND (CAST(:eventType AS text) IS NULL OR event_type = CAST(:eventType AS text))
                   AND (CAST(:actor AS uuid) IS NULL OR actor_user_id = CAST(:actor AS uuid))
                 ORDER BY created_at DESC
                 LIMIT :limit OFFSET :offset
                """)
            .param("org", organizationId)
            .param("eventType", eventType)
            .param("actor", actorUserId == null ? null : actorUserId.toString())
            .param("limit", limit)
            .param("offset", offset)
            .query(mapper).list();
    }
}
