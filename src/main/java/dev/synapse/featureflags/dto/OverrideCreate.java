package dev.synapse.featureflags.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Exactly one scope is required; a user override beats an org one at resolution time. */
public record OverrideCreate(UUID organizationId, UUID userId, @NotNull Boolean enabled, String note) {

    public boolean hasScope() {
        return organizationId != null || userId != null;
    }
}
