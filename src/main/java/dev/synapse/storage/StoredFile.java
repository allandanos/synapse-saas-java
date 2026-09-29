package dev.synapse.storage;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of {@code stored_files}: the org-scoped index. Bytes live in the backend.
 * {@code pending} means a presigned PUT was issued and the quota reserved;
 * {@code ready} means the bytes were verified.
 */
public record StoredFile(UUID id, UUID organizationId, String key, String name, String contentType, long sizeBytes,
                         String status, Instant deletedAt, UUID createdByUserId, Instant createdAt) {

    public static final String PENDING = "pending";
    public static final String READY = "ready";
}
