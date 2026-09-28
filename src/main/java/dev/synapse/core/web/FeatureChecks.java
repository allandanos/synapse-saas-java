package dev.synapse.core.web;

import java.util.UUID;

/** Feature-gate seam (implemented by {@code entitlements.FeatureGate}). */
public interface FeatureChecks {

    /** Throws 403 {@code feature_not_entitled} unless the organization's effective entitlements include the feature. */
    void require(UUID organizationId, String feature);
}
