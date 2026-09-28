package dev.synapse.authorization;

import dev.synapse.core.context.RequestContext;
import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.context.UserContext;
import dev.synapse.core.errors.PermissionDeniedError;
import dev.synapse.core.security.Principal;
import dev.synapse.core.web.PermissionChecks;
import dev.synapse.identity.User;
import dev.synapse.identity.UserRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The router-facing gate (reference: {@code authorization/dependencies.py:require_permission}).
 *
 * <p>API-key principals authorise against the key's scopes, intersected with
 * what the creating user can exercise RIGHT NOW: demoting or removing the
 * creator shrinks (or kills) every key they minted. A key with no recorded
 * creator is denied. Platform admins pass with the wildcard set. Everyone else
 * needs the permission in their membership's set. On success the enriched
 * {@link UserContext} (with permission keys) is bound for services and audit.
 */
@Component
public class PermissionGuard implements PermissionChecks {

    private final AuthorizationService authz;
    private final UserRepository users;
    private final TransactionTemplate tx;

    public PermissionGuard(AuthorizationService authz, UserRepository users, TransactionTemplate tx) {
        this.authz = authz;
        this.users = users;
        this.tx = tx;
    }

    @Override
    public void require(String permission, Principal principal, TenantContext tenant) {
        RequestContext ctx = RequestContextHolder.require();
        UserContext current = ctx.user();
        if (current != null && current.apiKeyScopes() != null) {
            requireForApiKey(permission, current, tenant);
            return;
        }
        if (principal.platformAdmin()) {
            ctx.setUser(UserContext.ofUser(principal.id(), principal.email(), true, Set.of("*")));
            return;
        }
        Set<String> keys = tx.execute(status -> authz.permissionKeysFor(principal.id(), tenant.organizationId()));
        if (!keys.contains(permission)) {
            throw new PermissionDeniedError("This action requires the '" + permission + "' permission", Map.of("permission", permission));
        }
        ctx.setUser(UserContext.ofUser(principal.id(), principal.email(), false, keys));
    }

    private void requireForApiKey(String permission, UserContext key, TenantContext tenant) {
        if (!key.apiKeyScopes().contains(permission)) {
            throw new PermissionDeniedError("API key lacks the '" + permission + "' scope",
                Map.of("permission", permission, "auth", "api_key"));
        }
        UUID creatorId = key.apiKeyCreatorId();
        if (creatorId == null) {
            throw new PermissionDeniedError("API key has no recorded creator to bound its authority",
                Map.of("permission", permission, "auth", "api_key", "reason", "unbounded_key"));
        }
        User creator = tx.execute(status -> users.findById(creatorId).orElse(null));
        if (creator == null || !creator.active()) {
            throw new PermissionDeniedError("API key creator is no longer active",
                Map.of("permission", permission, "auth", "api_key", "reason", "creator_inactive"));
        }
        if (!creator.platformAdmin()) {
            Set<String> creatorKeys = tx.execute(status -> authz.permissionKeysFor(creatorId, tenant.organizationId()));
            if (!creatorKeys.contains(permission)) {
                throw new PermissionDeniedError("API key creator no longer holds the '" + permission + "' permission",
                    Map.of("permission", permission, "auth", "api_key", "reason", "creator_lacks_permission"));
            }
        }
    }
}
