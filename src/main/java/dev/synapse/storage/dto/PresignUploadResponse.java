package dev.synapse.storage.dto;

import java.util.Map;
import java.util.UUID;

public record PresignUploadResponse(UUID id, String key, String url, String method, Map<String, String> headers,
                                    int expiresIn) {

    public static PresignUploadResponse of(UUID id, String key, String url, String contentType, int expiresIn) {
        return new PresignUploadResponse(id, key, url, "PUT", Map.of("Content-Type", contentType), expiresIn);
    }
}
