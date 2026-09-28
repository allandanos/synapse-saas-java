package dev.synapse.core.errors;

import java.util.Map;

/** config/plans.yaml is missing, malformed or inconsistent; every error rides in {@code errors[]}. */
public final class CatalogInvalidError extends DomainError {
    public static final int STATUS = 400;
    public static final String TITLE = "plan_catalog_invalid";

    public CatalogInvalidError(String message) {
        this(message, Map.of());
    }

    public CatalogInvalidError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
