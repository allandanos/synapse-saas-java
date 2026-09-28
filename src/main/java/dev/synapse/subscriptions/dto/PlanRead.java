package dev.synapse.subscriptions.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.synapse.subscriptions.Plan;
import java.util.List;
import java.util.UUID;

/** The contract's {@code PlanRead}. */
public record PlanRead(UUID id, String key, String name, String description, Long priceCents, String currency, String interval,
                       @JsonProperty("is_public") boolean isPublic, @JsonProperty("is_custom") boolean isCustom, int trialDays, int sortOrder,
                       List<PlanFeatureRead> features, List<PlanLimitRead> limits) {

    public static PlanRead from(Plan plan) {
        return new PlanRead(plan.id(), plan.key(), plan.name(), plan.description(), plan.priceCents(), plan.currency(), plan.interval(),
            plan.isPublic(), plan.isCustom(), plan.trialDays(), plan.sortOrder(),
            plan.features().stream().map(f -> new PlanFeatureRead(f.featureKey(), f.enabled())).toList(),
            plan.limits().stream().map(l -> new PlanLimitRead(l.metric(), l.limitValue(), l.softLimitRatio())).toList());
    }
}
