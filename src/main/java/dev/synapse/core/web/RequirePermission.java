package dev.synapse.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tenant route gate: resolves the tenant (404 for non-members, 403 while
 * suspended) then requires the permission (403 {@code permission_denied}).
 * API-key principals authorise against their scopes ∩ the creator's current permissions.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    String value();
}
