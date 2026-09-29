package dev.synapse.core.errors;

import java.util.Map;

/** No such feature flag (or it is archived). */
public final class FeatureFlagNotFoundError extends DomainError {
    public static final int STATUS = 404;
    public static final String TITLE = "feature_flag_not_found";

    public FeatureFlagNotFoundError(String message) {
        this(message, Map.of());
    }

    public FeatureFlagNotFoundError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
