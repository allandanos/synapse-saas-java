package dev.synapse.core.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Feature-flag gate (reference: {@code feature_flags/dependencies.py:require_flag}):
 * resolves the tenant, then requires the flag to be on for this org + user —
 * 403 {@code permission_denied} with {@code flag} and
 * {@code reason: "feature_flag_disabled"} otherwise.
 *
 * <p>Distinct from {@link RequireFeature}: flags gate code paths, not paid
 * tiers, so the failure carries the flag key instead of upgrade hints.
 * On the controller class it gates every handler in it.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFlag {
    String value();
}
