package dev.synapse.featureflags.dto;

import dev.synapse.featureflags.FeatureFlag;
import java.time.Instant;
import java.util.UUID;

public record FlagRead(UUID id, String key, String name, String description, boolean enabled, Integer rolloutPercentage,
                       Instant createdAt) {

    public static FlagRead from(FeatureFlag f) {
        return new FlagRead(f.id(), f.key(), f.name(), f.description(), f.enabled(), f.rolloutPercentage(), f.createdAt());
    }
}
