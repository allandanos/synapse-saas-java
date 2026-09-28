package dev.synapse.core.security;

import java.util.List;
import java.util.UUID;

/** The key behind a programmatic principal: its org (pinned tenant), scopes and the human who minted it. */
public record ApiKeyPrincipal(UUID keyId, String prefix, String name, UUID organizationId, String organizationSlug,
                              List<String> scopes, UUID creatorId) {
    public ApiKeyPrincipal {
        scopes = List.copyOf(scopes);
    }
}
