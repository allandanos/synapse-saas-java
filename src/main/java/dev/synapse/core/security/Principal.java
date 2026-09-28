package dev.synapse.core.security;

import java.util.UUID;

/**
 * The authenticated actor, credential-agnostic (reference: {@code identity/dependencies.py:Principal}).
 *
 * <p>JWT auth names a real user. API-key auth synthesises a principal whose
 * {@code id} is a fresh sentinel matching no {@code users} row; {@code apiKey}
 * carries the key. Downstream code stays credential-agnostic.
 */
public record Principal(UUID id, String email, String displayName, boolean platformAdmin, boolean active, ApiKeyPrincipal apiKey) {

    public static Principal ofUser(UUID id, String email, String displayName, boolean platformAdmin, boolean active) {
        return new Principal(id, email, displayName, platformAdmin, active, null);
    }

    public static Principal ofApiKey(ApiKeyPrincipal key) {
        return new Principal(UUID.randomUUID(), "apikey:" + key.prefix(), "API key " + key.name(), false, true, key);
    }

    public boolean isApiKey() {
        return apiKey != null;
    }
}
