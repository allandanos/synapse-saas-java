package dev.synapse.featureflags;

import dev.synapse.core.db.Rows;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code feature_flags} + {@code feature_flag_overrides}. */
@Repository
public class FeatureFlagRepository {

    private static final RowMapper<FeatureFlag> FLAG = (rs, i) -> new FeatureFlag(
        Rows.uuid(rs, "id"), rs.getString("key"), rs.getString("name"), rs.getString("description"), rs.getBoolean("enabled"),
        Rows.intOrNull(rs, "rollout_percentage"), Rows.instant(rs, "archived_at"), Rows.instant(rs, "created_at"));

    private static final RowMapper<FeatureFlagOverride> OVERRIDE = (rs, i) -> new FeatureFlagOverride(
        Rows.uuid(rs, "id"), rs.getString("flag_key"), Rows.uuid(rs, "organization_id"), Rows.uuid(rs, "user_id"),
        rs.getBoolean("enabled"), rs.getString("note"), Rows.instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public FeatureFlagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Live (non-archived) flag by key. */
    public Optional<FeatureFlag> findByKey(String key) {
        return jdbc.sql("SELECT * FROM feature_flags WHERE key = :key AND archived_at IS NULL")
            .param("key", key).query(FLAG).optional();
    }

    public List<FeatureFlag> listFlags() {
        return jdbc.sql("SELECT * FROM feature_flags WHERE archived_at IS NULL ORDER BY key").query(FLAG).list();
    }

    public FeatureFlag insertFlag(String key, String name, String description, boolean enabled, Integer rolloutPercentage) {
        jdbc.sql("""
                INSERT INTO feature_flags (id, key, name, description, enabled, rollout_percentage)
                VALUES (:id, :key, :name, :description, :enabled, :rollout)
                """)
            .param("id", UUID.randomUUID()).param("key", key).param("name", name).param("description", description)
            .param("enabled", enabled).param("rollout", rolloutPercentage)
            .update();
        return findByKey(key).orElseThrow();
    }

    public FeatureFlag updateFlag(String key, boolean enabled, Integer rolloutPercentage) {
        jdbc.sql("UPDATE feature_flags SET enabled = :enabled, rollout_percentage = :rollout, updated_at = now() WHERE key = :key")
            .param("key", key).param("enabled", enabled).param("rollout", rolloutPercentage).update();
        return findByKey(key).orElseThrow();
    }

    public List<FeatureFlagOverride> listOverrides(String flagKey) {
        return jdbc.sql("SELECT * FROM feature_flag_overrides WHERE flag_key = :key ORDER BY created_at DESC, id")
            .param("key", flagKey).query(OVERRIDE).list();
    }

    /** The user override when a user is given, else the org-wide one (user_id IS NULL) — the reference's lookup order. */
    public Optional<FeatureFlagOverride> findOverride(String flagKey, UUID organizationId, UUID userId) {
        if (userId != null) {
            return jdbc.sql("SELECT * FROM feature_flag_overrides WHERE flag_key = :key AND user_id = :user")
                .param("key", flagKey).param("user", userId).query(OVERRIDE).optional();
        }
        if (organizationId != null) {
            return jdbc.sql("SELECT * FROM feature_flag_overrides WHERE flag_key = :key AND organization_id = :org AND user_id IS NULL")
                .param("key", flagKey).param("org", organizationId).query(OVERRIDE).optional();
        }
        return Optional.empty();
    }

    public Optional<FeatureFlagOverride> findOverrideById(UUID id) {
        return jdbc.sql("SELECT * FROM feature_flag_overrides WHERE id = :id").param("id", id).query(OVERRIDE).optional();
    }

    public FeatureFlagOverride insertOverride(String flagKey, UUID organizationId, UUID userId, boolean enabled, String note) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO feature_flag_overrides (id, flag_key, organization_id, user_id, enabled, note)
                VALUES (:id, :key, :org, :user, :enabled, :note)
                """)
            .param("id", id).param("key", flagKey).param("org", organizationId).param("user", userId)
            .param("enabled", enabled).param("note", note)
            .update();
        return findOverrideById(id).orElseThrow();
    }

    public FeatureFlagOverride updateOverride(UUID id, boolean enabled, String note) {
        jdbc.sql("UPDATE feature_flag_overrides SET enabled = :enabled, note = :note, updated_at = now() WHERE id = :id")
            .param("id", id).param("enabled", enabled).param("note", note).update();
        return findOverrideById(id).orElseThrow();
    }

    public void deleteOverride(UUID id) {
        jdbc.sql("DELETE FROM feature_flag_overrides WHERE id = :id").param("id", id).update();
    }
}
