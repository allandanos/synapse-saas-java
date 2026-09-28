package dev.synapse.entitlements;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Row of {@code entitlements}: a time-boxed, source-tagged feature (or {@code limit:<metric>}) grant. */
public record Entitlement(UUID id, UUID organizationId, String featureKey, String source, boolean enabled, Instant startsAt, Instant endsAt,
                          String note, Long limitValue, UUID createdByUserId, Instant revokedAt, LocalDateTime createdAt) {

    public EntitlementResolver.EntitlementGrant toGrant() {
        return new EntitlementResolver.EntitlementGrant(featureKey, source, enabled, startsAt, endsAt, revokedAt, limitValue);
    }
}
