package dev.synapse.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Feature gate (reference: {@code entitlements/dependencies.py:require_feature}):
 * resolves the tenant, then requires the organization's effective entitlements
 * to include the feature — 403 {@code feature_not_entitled} with
 * {@code feature}, {@code current_plan}, {@code available_in[]}, {@code upgrade_url} otherwise.
 * Combine with {@link RequirePermission} when the route also needs a permission.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFeature {
    String value();
}
