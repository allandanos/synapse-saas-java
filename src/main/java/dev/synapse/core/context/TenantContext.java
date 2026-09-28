package dev.synapse.core.context;

import java.util.UUID;

/** The organization a request is scoped to; {@code platform} = operator scope (no tenant filtering). */
public record TenantContext(UUID organizationId, String slug, boolean platform) {

    public static final UUID PLATFORM_ORG_ID = new UUID(0L, 0L);

    public static TenantContext of(UUID organizationId, String slug) {
        return new TenantContext(organizationId, slug, false);
    }

    public static TenantContext platformScope() {
        return new TenantContext(PLATFORM_ORG_ID, "platform", true);
    }
}
