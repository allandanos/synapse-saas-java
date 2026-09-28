package dev.synapse.identity;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code refresh_tokens}: opaque token stored as its SHA-256; {@code organizationId} scopes the session (switch-org). */
public record RefreshToken(UUID id, UUID userId, String tokenHash, UUID organizationId, Instant expiresAt,
                           Instant revokedAt, UUID replacedByTokenId, String userAgent, String ip, Instant createdAt) {

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
