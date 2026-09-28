package dev.synapse.core.errors;

import java.util.Map;

/** Metric key not in the registry. */
public final class UnknownMetricError extends DomainError {
    public static final int STATUS = 422;
    public static final String TITLE = "unknown_metric";

    public UnknownMetricError(String message) {
        this(message, Map.of());
    }

    public UnknownMetricError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
