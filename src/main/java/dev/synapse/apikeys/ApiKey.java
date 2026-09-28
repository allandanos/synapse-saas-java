package dev.synapse.apikeys;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Row of {@code api_keys}: only the SHA-256 of the plaintext is stored; {@code prefix} is for display. */
public record ApiKey(UUID id, UUID organizationId, String name, String prefix, String keyHash, List<String> scopes,
                     Instant expiresAt, Instant lastUsedAt, Instant revokedAt, UUID createdByUserId, Instant createdAt) {

    public boolean isActive(Instant now) {
        if (revokedAt != null) {
            return false;
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
