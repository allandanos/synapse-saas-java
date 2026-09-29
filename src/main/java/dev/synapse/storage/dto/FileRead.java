package dev.synapse.storage.dto;

import dev.synapse.storage.StoredFile;
import java.time.Instant;
import java.util.UUID;

public record FileRead(UUID id, UUID organizationId, String key, String name, String contentType, long sizeBytes,
                       String status, Instant createdAt) {

    public static FileRead from(StoredFile f) {
        return new FileRead(f.id(), f.organizationId(), f.key(), f.name(), f.contentType(), f.sizeBytes(), f.status(),
            f.createdAt());
    }
}
