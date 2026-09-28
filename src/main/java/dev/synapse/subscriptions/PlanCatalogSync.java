package dev.synapse.subscriptions;

import dev.synapse.subscriptions.PlanCatalog.MetricDefinition;
import dev.synapse.subscriptions.PlanCatalog.OverageDefinition;
import dev.synapse.subscriptions.PlanCatalog.PlanDefinition;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog → DB sync (reference: {@code subscriptions/sync.py}).
 *
 * <p>Idempotent upsert keyed on natural keys. Never deletes a plan (removals
 * become {@code archived_at}), never touches {@code provider_refs}, never
 * rewrites existing subscriptions' {@code plan_snapshot} — YAML price changes
 * must not rewrite history.
 */
@Service
public class PlanCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(PlanCatalogSync.class);

    public record SyncResult(int featuresAdded, int metricsAdded, int plansAdded, int plansUpdated, int plansArchived) {
        public Map<String, Integer> summary() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("features_added", featuresAdded);
            m.put("metrics_added", metricsAdded);
            m.put("plans_added", plansAdded);
            m.put("plans_updated", plansUpdated);
            m.put("plans_archived", plansArchived);
            return m;
        }
    }

    private final PlanRepository plans;
    private final MetricRepository metrics;

    public PlanCatalogSync(PlanRepository plans, MetricRepository metrics) {
        this.plans = plans;
        this.metrics = metrics;
    }

    @Transactional
    public SyncResult sync(PlanCatalog catalog) {
        Instant now = Instant.now();
        int featuresAdded = 0;
        int metricsAdded = 0;
        int plansAdded = 0;
        int plansUpdated = 0;
        int plansArchived = 0;

        // ── Features registry ───────────────────────────────────────────────────
        Set<String> existingFeatures = plans.existingFeatureKeys();
        for (PlanCatalog.FeatureDefinition f : catalog.features()) {
            if (existingFeatures.contains(f.key())) {
                continue;
            }
            plans.insertFeature(f.key(), f.name(), f.category());
            featuresAdded++;
        }

        // ── Metrics registry (the catalog is the source of truth for a metric's shape too) ──
        Map<String, Metric> existingMetrics = metrics.all().stream().collect(Collectors.toMap(Metric::key, Function.identity()));
        for (MetricDefinition m : catalog.metrics()) {
            Integer overageUnit = m.overage() == null ? null : m.overage().unit();
            Long overagePrice = m.overage() == null ? null : m.overage().priceCents();
            if (existingMetrics.containsKey(m.key())) {
                metrics.update(m.key(), m.name(), m.kind(), m.unit(), overageUnit, overagePrice);
                continue;
            }
            metrics.insert(m.key(), m.name(), m.kind(), m.unit(), overageUnit, overagePrice);
            metricsAdded++;
        }

        // ── Plans ───────────────────────────────────────────────────────────────
        Map<String, Plan> existingPlans = plans.all().stream().collect(Collectors.toMap(Plan::key, Function.identity()));
        List<PlanDefinition> definitions = catalog.plans();
        for (int order = 0; order < definitions.size(); order++) {
            PlanDefinition def = definitions.get(order);
            PlanCatalog.Defaults defaults = catalog.defaults();
            String currency = def.currency() != null ? def.currency() : defaults.currency();
            String interval = def.interval() != null ? def.interval() : defaults.interval();
            boolean isCustom = def.isCustom() || def.isCustomPriced();
            int trialDays = def.trialDays() != null ? def.trialDays() : defaults.trialDays();
            int sortOrder = def.sortOrder() != 0 ? def.sortOrder() : order;

            Plan plan = existingPlans.get(def.key());
            UUID planId;
            if (plan == null) {
                planId = plans.insert(def.key(), def.name(), def.description(), def.priceCents(), currency, interval, def.isPublic(), isCustom, trialDays, sortOrder);
                plansAdded++;
            } else {
                planId = plan.id();
                boolean changed = !Objects.equals(plan.name(), def.name()) || !Objects.equals(plan.description(), def.description())
                    || !Objects.equals(plan.priceCents(), def.priceCents()) || !Objects.equals(plan.currency(), currency)
                    || !Objects.equals(plan.interval(), interval) || plan.isPublic() != def.isPublic() || plan.isCustom() != isCustom
                    || plan.trialDays() != trialDays || plan.sortOrder() != sortOrder || plan.archivedAt() != null; // re-listing revives
                if (changed) {
                    plans.update(planId, def.name(), def.description(), def.priceCents(), currency, interval, def.isPublic(), isCustom, trialDays, sortOrder, null);
                    plansUpdated++;
                }
            }
            syncPlanFeatures(planId, plan == null ? List.of() : plan.features(), def.features());
            syncPlanLimits(planId, plan == null ? List.of() : plan.limits(), def, catalog);
        }

        // ── Archive plans removed from the catalog ─────────────────────────────
        Set<String> catalogKeys = definitions.stream().map(PlanDefinition::key).collect(Collectors.toSet());
        for (Plan plan : existingPlans.values()) {
            if (!catalogKeys.contains(plan.key()) && plan.archivedAt() == null) {
                plans.archive(plan.id(), now);
                plansArchived++;
            }
        }

        SyncResult result = new SyncResult(featuresAdded, metricsAdded, plansAdded, plansUpdated, plansArchived);
        log.info("plans_synced {}", result.summary());
        return result;
    }

    private void syncPlanFeatures(UUID planId, List<PlanFeature> existing, List<String> wanted) {
        Set<String> present = existing.stream().map(PlanFeature::featureKey).collect(Collectors.toSet());
        for (String key : wanted) {
            if (!present.contains(key)) {
                plans.insertPlanFeature(planId, key);
                present.add(key);
            }
        }
        Set<String> wantedSet = Set.copyOf(wanted);
        for (PlanFeature pf : existing) {
            if (!wantedSet.contains(pf.featureKey())) {
                plans.deletePlanFeature(planId, pf.featureKey());
            }
        }
    }

    private void syncPlanLimits(UUID planId, List<PlanLimit> existing, PlanDefinition def, PlanCatalog catalog) {
        Map<String, MetricDefinition> metricDefs = new HashMap<>();
        catalog.metrics().forEach(m -> metricDefs.put(m.key(), m));
        for (Map.Entry<String, Long> entry : def.limits().entrySet()) {
            String metric = entry.getKey();
            // A metric's own ratio wins; otherwise the catalog default.
            Double soft = metricDefs.containsKey(metric) ? metricDefs.get(metric).softLimitRatio() : null;
            if (soft == null) {
                soft = catalog.defaults().softLimitRatio();
            }
            Optional<OverageDefinition> overage = catalog.overageFor(def.key(), metric);
            plans.upsertPlanLimit(planId, metric, entry.getValue(), soft,
                overage.map(OverageDefinition::unit).orElse(null), overage.map(OverageDefinition::priceCents).orElse(null));
        }
        for (PlanLimit pl : existing) {
            if (!def.limits().containsKey(pl.metric())) {
                plans.deletePlanLimit(planId, pl.metric());
            }
        }
    }
}
