package dev.synapse.featureflags;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of {@code feature_flags}: a deployment-level toggle, not an entitlement.
 * Entitlements answer "what did this org pay for?"; flags answer "is this code
 * path on yet?". {@code rolloutPercentage} non-null ⇒ the global default is a
 * deterministic bucket test instead of {@code enabled}.
 */
public record FeatureFlag(UUID id, String key, String name, String description, boolean enabled, Integer rolloutPercentage,
                          Instant archivedAt, Instant createdAt) {}
