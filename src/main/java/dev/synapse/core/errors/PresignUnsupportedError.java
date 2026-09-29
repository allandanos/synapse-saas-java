package dev.synapse.core.errors;

import java.util.Map;

/** The configured backend has no presigned URLs (local disk); callers stream through the API instead. */
public final class PresignUnsupportedError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "presign_unsupported";

    public PresignUnsupportedError(String message) {
        this(message, Map.of());
    }

    public PresignUnsupportedError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
