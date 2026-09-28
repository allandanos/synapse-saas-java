package dev.synapse.core.context;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated actor (reference: {@code core/context.py:UserContext}).
 *
 * <p>When {@code apiKeyId} is set the actor is a programmatic key, not a user:
 * {@code userId} is a random sentinel that matches no {@code users} row (audit
 * attributes key actions to {@code apiKeyCreatorId}), {@code email} names the
 * key, and permissions are the key's scopes bounded by the creating user's
 * current permissions.
 */
public record UserContext(
    UUID userId,
    String email,
    boolean platformAdmin,
    Set<String> permissionKeys,
    UUID apiKeyId,
    Set<String> apiKeyScopes,
    UUID apiKeyCreatorId
) {
    public UserContext {
        permissionKeys = permissionKeys == null ? Set.of() : Set.copyOf(permissionKeys);
        apiKeyScopes = apiKeyScopes == null ? null : Set.copyOf(apiKeyScopes);
    }

    public static UserContext ofUser(UUID userId, String email, boolean platformAdmin, Set<String> permissionKeys) {
        return new UserContext(userId, email, platformAdmin, permissionKeys, null, null, null);
    }

    public boolean isApiKey() {
        return apiKeyId != null;
    }
}
