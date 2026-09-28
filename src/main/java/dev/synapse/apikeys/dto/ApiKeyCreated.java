package dev.synapse.apikeys.dto;

import dev.synapse.apikeys.ApiKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link ApiKeyRead} plus the plaintext — which appears exactly once, here. */
public record ApiKeyCreated(UUID id, String name, String prefix, List<String> scopes, Instant expiresAt, Instant lastUsedAt,
                            Instant revokedAt, Instant createdAt, String key) {

    public static ApiKeyCreated from(ApiKey k, String plaintext) {
        return new ApiKeyCreated(k.id(), k.name(), k.prefix(), k.scopes(), k.expiresAt(), k.lastUsedAt(), k.revokedAt(), k.createdAt(), plaintext);
    }
}
