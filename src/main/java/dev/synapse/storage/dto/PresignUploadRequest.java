package dev.synapse.storage.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Reserve quota + get a PUT URL for an object the client uploads directly. */
public record PresignUploadRequest(
    @NotNull @Size(min = 1, max = 255) String name,
    @Size(max = 128) String contentType,
    /** Single-PUT ceiling: 5 GiB. */
    @NotNull @Min(1) @Max(5L * 1024 * 1024 * 1024) Long sizeBytes
) {
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    public String contentTypeOrDefault() {
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
    }
}
