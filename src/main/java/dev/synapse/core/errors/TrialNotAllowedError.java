package dev.synapse.core.errors;

import java.util.Map;

/** The plan has no trial period or a trial is already running. */
public final class TrialNotAllowedError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "trial_not_allowed";

    public TrialNotAllowedError(String message) {
        this(message, Map.of());
    }

    public TrialNotAllowedError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
