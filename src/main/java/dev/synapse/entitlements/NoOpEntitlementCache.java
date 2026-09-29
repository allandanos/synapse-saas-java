package dev.synapse.entitlements;

import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import java.util.Optional;
import java.util.UUID;

/**
 * Compute per request. Not a bean: {@link VersionedEntitlementCache} is what
 * the application wires; this stays as the "no cache at all" double for tests
 * that assert a recomputation on every call.
 */
public class NoOpEntitlementCache implements EntitlementCache {

    @Override
    public Optional<EffectiveEntitlements> get(UUID organizationId) {
        return Optional.empty();
    }

    @Override
    public void put(UUID organizationId, EffectiveEntitlements effective) {
        // nothing to keep
    }

    @Override
    public void invalidate(UUID organizationId) {
        // nothing to drop
    }
}
