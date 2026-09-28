package dev.synapse.entitlements;

import dev.synapse.core.web.FeatureChecks;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The router-facing feature gate behind {@code @RequireFeature} (reference: {@code entitlements/dependencies.py}). */
@Component
public class FeatureGate implements FeatureChecks {

    private final EntitlementService entitlements;

    public FeatureGate(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    public void require(UUID organizationId, String feature) {
        entitlements.requireFeature(organizationId, feature);
    }
}
