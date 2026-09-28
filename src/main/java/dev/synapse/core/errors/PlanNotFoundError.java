package dev.synapse.core.errors;

import java.util.Map;

/** Plan key not in the catalog (or archived). */
public final class PlanNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "plan_not_found";

    public PlanNotFoundError(String message) {
        this(message, Map.of());
    }

    public PlanNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
