package dev.synapse.core.errors;

import java.util.Map;

/** A presigned upload was completed before the object landed, or with a different size. */
public final class UploadIncompleteError extends DomainError {
    public static final int STATUS = 409;
    public static final String TITLE = "upload_incomplete";

    public UploadIncompleteError(String message) {
        this(message, Map.of());
    }

    public UploadIncompleteError(String message, Map<String, Object> extras) {
        super(STATUS, TITLE, message, extras);
    }
}
