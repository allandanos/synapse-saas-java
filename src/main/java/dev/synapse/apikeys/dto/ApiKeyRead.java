package dev.synapse.apikeys.dto;

import dev.synapse.apikeys.ApiKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeyRead(UUID id, String name, String prefix, List<String> scopes, Instant expiresAt, Instant lastUsedAt,
                         Instant revokedAt, Instant createdAt) {

    public static ApiKeyRead from(ApiKey k) {
        return new ApiKeyRead(k.id(), k.name(), k.prefix(), k.scopes(), k.expiresAt(), k.lastUsedAt(), k.revokedAt(), k.createdAt());
    }
}
