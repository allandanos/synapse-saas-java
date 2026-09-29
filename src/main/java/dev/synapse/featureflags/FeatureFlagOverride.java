package dev.synapse.featureflags;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code feature_flag_overrides}: an org- or user-level pin. User beats org. */
public record FeatureFlagOverride(UUID id, String flagKey, UUID organizationId, UUID userId, boolean enabled, String note,
                                  Instant createdAt) {}
