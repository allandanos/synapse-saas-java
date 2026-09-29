package dev.synapse.featureflags.dto;

import dev.synapse.featureflags.FeatureFlagOverride;
import java.time.Instant;
import java.util.UUID;

public record OverrideRead(UUID id, String flagKey, UUID organizationId, UUID userId, boolean enabled, String note,
                           Instant createdAt) {

    public static OverrideRead from(FeatureFlagOverride o) {
        return new OverrideRead(o.id(), o.flagKey(), o.organizationId(), o.userId(), o.enabled(), o.note(), o.createdAt());
    }
}
