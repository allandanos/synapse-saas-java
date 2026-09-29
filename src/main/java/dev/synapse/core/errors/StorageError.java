package dev.synapse.core.errors;

import java.util.Map;

/** Malformed key, unusable upload, or a backend that refused the operation. */
public final class StorageError extends DomainError {
    public static final int STATUS = 400;
    public static final String TITLE = "storage_error";

    public StorageError(String message) {
        this(message, Map.of());
    }

    public StorageError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
