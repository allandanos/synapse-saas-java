package dev.synapse.core.errors;

import java.util.Map;

/** Feature gate: {@code feature}, {@code current_plan}, {@code available_in}, {@code upgrade_url}. */
public final class FeatureNotEntitledError extends DomainError {
    public static final int STATUS = 403;
    public static final String TITLE = "feature_not_entitled";

    public FeatureNotEntitledError(String message) {
        this(message, Map.of());
    }

    public FeatureNotEntitledError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
