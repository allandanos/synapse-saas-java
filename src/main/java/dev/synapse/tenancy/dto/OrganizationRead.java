package dev.synapse.tenancy.dto;

import dev.synapse.tenancy.Organization;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record OrganizationRead(UUID id, String slug, String name, String status, UUID ownerUserId,
                               Map<String, Object> settings, LocalDateTime createdAt) {

    public static OrganizationRead from(Organization o) {
        return new OrganizationRead(o.id(), o.slug(), o.name(), o.status(), o.ownerUserId(), o.settings(), o.createdAt());
    }
}
