package dev.synapse.subscriptions;

import dev.synapse.core.db.Rows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code plans} + {@code plan_features} + {@code plan_limits} + the {@code features} registry. */
@Repository
public class PlanRepository {

    /** A plan row before its features/limits are attached. */
    record PlanRow(UUID id, String key, String name, String description, Long priceCents, String currency, String interval,
                   boolean isPublic, boolean isCustom, int trialDays, int sortOrder, Instant archivedAt) {}

    private static final RowMapper<PlanRow> ROW = (rs, i) -> new PlanRow(
        Rows.uuid(rs, "id"), rs.getString("key"), rs.getString("name"), rs.getString("description"), Rows.longOrNull(rs, "price_cents"),
        rs.getString("currency"), rs.getString("interval"), rs.getBoolean("is_public"), rs.getBoolean("is_custom"),
        rs.getInt("trial_days"), rs.getInt("sort_order"), Rows.instant(rs, "archived_at"));

    private static final String COLUMNS = "id, key, name, description, price_cents, currency, interval, is_public, is_custom, trial_days, sort_order, archived_at";

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Reads ────────────────────────────────────────────────────────────────────

    public Optional<Plan> findByKey(String key, boolean includeArchived) {
        String sql = "SELECT " + COLUMNS + " FROM plans WHERE key = :key" + (includeArchived ? "" : " AND archived_at IS NULL");
        return jdbc.sql(sql).param("key", key).query(ROW).optional().map(this::hydrate);
    }

    public Optional<Plan> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM plans WHERE id = :id").param("id", id).query(ROW).optional().map(this::hydrate);
    }

    /** Public, non-archived plans in catalog order (the {@code GET /v1/plans} page). */
    public List<Plan> listPublic(int limit, int offset) {
        List<PlanRow> rows = jdbc.sql("SELECT " + COLUMNS + " FROM plans WHERE is_public = TRUE AND archived_at IS NULL "
                + "ORDER BY sort_order, created_at, key LIMIT :limit OFFSET :offset")
            .param("limit", limit).param("offset", offset).query(ROW).list();
        return hydrate(rows);
    }

    public long countPublic() {
        return jdbc.sql("SELECT count(*) FROM plans WHERE is_public = TRUE AND archived_at IS NULL").query(Long.class).single();
    }

    /** Every plan, archived ones included (the sync diff). */
    public List<Plan> all() {
        return hydrate(jdbc.sql("SELECT " + COLUMNS + " FROM plans ORDER BY sort_order, created_at, key").query(ROW).list());
    }

    /** Keys of the plans whose feature set includes {@code feature} (sorted, distinct; archived/private included like the reference). */
    public List<String> keysWithFeature(String feature) {
        return jdbc.sql("""
                SELECT DISTINCT p.key FROM plans p JOIN plan_features pf ON pf.plan_id = p.id
                WHERE pf.feature_key = :feature AND pf.enabled = TRUE ORDER BY p.key
                """)
            .param("feature", feature).query(String.class).list();
    }

    // ── Writes (catalog sync) ────────────────────────────────────────────────────

    public UUID insert(String key, String name, String description, Long priceCents, String currency, String interval,
                       boolean isPublic, boolean isCustom, int trialDays, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO plans (id, key, name, description, price_cents, currency, interval, is_public, is_custom, trial_days, sort_order,
                                   provider_refs, metadata, archived_at)
                VALUES (:id, :key, :name, :description, :price, :currency, :interval, :isPublic, :isCustom, :trialDays, :sortOrder,
                        '{}'::jsonb, '{}'::jsonb, NULL)
                """)
            .param("id", id).param("key", key).param("name", name).param("description", description).param("price", priceCents)
            .param("currency", currency).param("interval", interval).param("isPublic", isPublic).param("isCustom", isCustom)
            .param("trialDays", trialDays).param("sortOrder", sortOrder)
            .update();
        return id;
    }

    public void update(UUID id, String name, String description, Long priceCents, String currency, String interval,
                       boolean isPublic, boolean isCustom, int trialDays, int sortOrder, Instant archivedAt) {
        jdbc.sql("""
                UPDATE plans SET name = :name, description = :description, price_cents = :price, currency = :currency, interval = :interval,
                       is_public = :isPublic, is_custom = :isCustom, trial_days = :trialDays, sort_order = :sortOrder,
                       archived_at = :archivedAt, updated_at = now()
                WHERE id = :id
                """)
            .param("id", id).param("name", name).param("description", description).param("price", priceCents)
            .param("currency", currency).param("interval", interval).param("isPublic", isPublic).param("isCustom", isCustom)
            .param("trialDays", trialDays).param("sortOrder", sortOrder).param("archivedAt", Rows.at(archivedAt))
            .update();
    }

    /**
     * Merge one provider's refs into {@code plans.provider_refs} (plan sync).
     * {@code ||} keeps the other providers' entries: a catalog can be pushed to
     * Stripe and Paddle without either erasing the other.
     */
    public void mergeProviderRefs(UUID id, String provider, String refsJson) {
        jdbc.sql("UPDATE plans SET provider_refs = provider_refs || jsonb_build_object(:provider, CAST(:refs AS jsonb)), "
                + "updated_at = now() WHERE id = :id")
            .param("provider", provider).param("refs", refsJson).param("id", id).update();
    }

    public void archive(UUID id, Instant at) {
        jdbc.sql("UPDATE plans SET archived_at = :at, updated_at = now() WHERE id = :id").param("at", Rows.at(at)).param("id", id).update();
    }

    public void insertPlanFeature(UUID planId, String featureKey) {
        jdbc.sql("INSERT INTO plan_features (plan_id, feature_key, enabled) VALUES (:plan, :feature, TRUE)")
            .param("plan", planId).param("feature", featureKey).update();
    }

    public void deletePlanFeature(UUID planId, String featureKey) {
        jdbc.sql("DELETE FROM plan_features WHERE plan_id = :plan AND feature_key = :feature")
            .param("plan", planId).param("feature", featureKey).update();
    }

    public void upsertPlanLimit(UUID planId, String metric, Long limitValue, Double softLimitRatio, Integer overageUnit, Long overagePriceCents) {
        jdbc.sql("""
                INSERT INTO plan_limits (plan_id, metric, limit_value, soft_limit_ratio, overage_unit, overage_price_cents)
                VALUES (:plan, :metric, :limit, :soft, :unit, :price)
                ON CONFLICT (plan_id, metric) DO UPDATE SET limit_value = EXCLUDED.limit_value, soft_limit_ratio = EXCLUDED.soft_limit_ratio,
                    overage_unit = EXCLUDED.overage_unit, overage_price_cents = EXCLUDED.overage_price_cents
                """)
            .param("plan", planId).param("metric", metric).param("limit", limitValue)
            .param("soft", softLimitRatio == null ? null : java.math.BigDecimal.valueOf(softLimitRatio))
            .param("unit", overageUnit).param("price", overagePriceCents)
            .update();
    }

    public void deletePlanLimit(UUID planId, String metric) {
        jdbc.sql("DELETE FROM plan_limits WHERE plan_id = :plan AND metric = :metric").param("plan", planId).param("metric", metric).update();
    }

    // ── features registry ────────────────────────────────────────────────────────

    public Set<String> existingFeatureKeys() {
        return jdbc.sql("SELECT key FROM features").query(String.class).list().stream().collect(Collectors.toSet());
    }

    public void insertFeature(String key, String name, String category) {
        jdbc.sql("INSERT INTO features (key, name, category) VALUES (:key, :name, :category)")
            .param("key", key).param("name", name).param("category", category).update();
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private Plan hydrate(PlanRow row) {
        return hydrate(List.of(row)).get(0);
    }

    private List<Plan> hydrate(List<PlanRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String[] ids = rows.stream().map(r -> r.id().toString()).toArray(String[]::new);
        Map<UUID, List<PlanFeature>> features = jdbc.sql("SELECT plan_id, feature_key, enabled FROM plan_features WHERE plan_id = ANY(CAST(:ids AS uuid[])) ORDER BY feature_key")
            .param("ids", ids)
            .query((rs, i) -> Map.entry(Rows.uuid(rs, "plan_id"), new PlanFeature(rs.getString("feature_key"), rs.getBoolean("enabled"))))
            .list().stream()
            .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        Map<UUID, List<PlanLimit>> limits = jdbc.sql("SELECT plan_id, metric, limit_value, soft_limit_ratio, overage_unit, overage_price_cents FROM plan_limits WHERE plan_id = ANY(CAST(:ids AS uuid[])) ORDER BY metric")
            .param("ids", ids)
            .query((rs, i) -> Map.entry(Rows.uuid(rs, "plan_id"), new PlanLimit(rs.getString("metric"), Rows.longOrNull(rs, "limit_value"),
                Rows.doubleOrNull(rs, "soft_limit_ratio"), Rows.intOrNull(rs, "overage_unit"), Rows.longOrNull(rs, "overage_price_cents"))))
            .list().stream()
            .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        List<Plan> plans = new ArrayList<>();
        for (PlanRow r : rows) {
            plans.add(new Plan(r.id(), r.key(), r.name(), r.description(), r.priceCents(), r.currency(), r.interval(), r.isPublic(), r.isCustom(),
                r.trialDays(), r.sortOrder(), r.archivedAt(), features.getOrDefault(r.id(), List.of()), limits.getOrDefault(r.id(), List.of())));
        }
        return plans;
    }
}
