package dev.synapse.entitlements;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EntitlementRepository {

    private static final RowMapper<Entitlement> MAPPER = (rs, i) -> new Entitlement(
        Rows.uuid(rs, "id"), Rows.uuid(rs, "organization_id"), rs.getString("feature_key"), rs.getString("source"), rs.getBoolean("enabled"),
        Rows.instant(rs, "starts_at"), Rows.instant(rs, "ends_at"), rs.getString("note"), Rows.longOrNull(rs, "limit_value"),
        Rows.uuid(rs, "created_by_user_id"), Rows.instant(rs, "revoked_at"), Rows.local(rs, "created_at"));

    private final JdbcClient jdbc;

    public EntitlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Entitlement insert(UUID organizationId, String featureKey, String source, boolean enabled, Instant startsAt, Instant endsAt,
                              String note, Long limitValue, UUID createdByUserId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO entitlements (id, organization_id, feature_key, source, enabled, starts_at, ends_at, note, limit_value, created_by_user_id)
                VALUES (:id, :org, :feature, :source, :enabled, :startsAt, :endsAt, :note, :limit, :createdBy)
                """)
            .param("id", id).param("org", organizationId).param("feature", featureKey).param("source", source).param("enabled", enabled)
            .param("startsAt", Rows.at(startsAt)).param("endsAt", Rows.at(endsAt)).param("note", note).param("limit", limitValue)
            .param("createdBy", createdByUserId)
            .update();
        return findById(id).orElseThrow();
    }

    public Optional<Entitlement> findById(UUID id) {
        return jdbc.sql("SELECT * FROM entitlements WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Un-revoked grants of an organization (time windows are evaluated by the resolver). */
    public List<Entitlement> unrevokedForOrg(UUID organizationId) {
        return jdbc.sql("SELECT * FROM entitlements WHERE organization_id = :org AND revoked_at IS NULL ORDER BY created_at, id")
            .param("org", organizationId).query(MAPPER).list();
    }

    public void revoke(UUID id, Instant at) {
        jdbc.sql("UPDATE entitlements SET revoked_at = :at, updated_at = now() WHERE id = :id").param("at", Rows.at(at)).param("id", id).update();
    }
}
