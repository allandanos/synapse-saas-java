package dev.synapse.tenancy;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** Row of {@code organizations}. */
public record Organization(UUID id, String slug, String name, String status, UUID ownerUserId,
                           Map<String, Object> settings, LocalDateTime createdAt, LocalDateTime deletedAt) {

    public boolean isActive() {
        return "active".equals(status);
    }
}
