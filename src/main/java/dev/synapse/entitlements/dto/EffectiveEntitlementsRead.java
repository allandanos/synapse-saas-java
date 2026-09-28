package dev.synapse.entitlements.dto;

import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The contract's {@code EffectiveEntitlementsRead}: sorted features, {@code limits: {metric: {value, soft_limit_ratio}}}. */
public record EffectiveEntitlementsRead(UUID organizationId, String planKey, String subscriptionStatus, List<String> features,
                                        Map<String, Map<String, Object>> limits) {

    public static EffectiveEntitlementsRead from(EffectiveEntitlements effective) {
        Map<String, Map<String, Object>> limits = new LinkedHashMap<>();
        for (Map.Entry<String, Limit> entry : effective.limits().entrySet()) {
            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("value", entry.getValue().value());
            limit.put("soft_limit_ratio", entry.getValue().softLimitRatio());
            limits.put(entry.getKey(), limit);
        }
        return new EffectiveEntitlementsRead(effective.organizationId(), effective.planKey(), effective.subscriptionStatus(),
            effective.sortedFeatures(), limits);
    }
}
