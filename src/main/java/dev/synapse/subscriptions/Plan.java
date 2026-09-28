package dev.synapse.subscriptions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Row of {@code plans} with its {@code plan_features} and {@code plan_limits} (the reference's eager-loaded Plan). */
public record Plan(UUID id, String key, String name, String description, Long priceCents, String currency, String interval,
                   boolean isPublic, boolean isCustom, int trialDays, int sortOrder, Instant archivedAt,
                   List<PlanFeature> features, List<PlanLimit> limits) {

    public Plan {
        features = List.copyOf(features);
        limits = List.copyOf(limits);
    }

    public boolean isFree() {
        return priceCents != null && priceCents == 0;
    }

    /** {@code year} → 365 days, anything else → 30 days (reference: {@code MONTH}/{@code YEAR}). */
    public java.time.Duration intervalLength() {
        return "year".equals(interval) ? java.time.Duration.ofDays(365) : java.time.Duration.ofDays(30);
    }
}
