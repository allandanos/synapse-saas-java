package dev.synapse.entitlements;

import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import java.util.Optional;
import java.util.UUID;

/**
 * Seam for the reference's Redis-backed {@code VersionedCache("entl")}. The
 * default implementation caches nothing: every read recomputes from Postgres
 * and every subscription/grant change calls {@link #invalidate} so a real
 * cache can drop in without touching the services.
 */
public interface EntitlementCache {

    Optional<EffectiveEntitlements> get(UUID organizationId);

    void put(UUID organizationId, EffectiveEntitlements effective);

    void invalidate(UUID organizationId);
}
