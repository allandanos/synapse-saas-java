package dev.synapse.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.synapse.identity.User;
import java.time.Instant;
import java.util.UUID;

public record UserRead(
    UUID id,
    String email,
    String displayName,
    String avatarUrl,
    @JsonProperty("is_platform_admin") boolean platformAdmin,
    @JsonProperty("is_active") boolean active,
    Instant lastLoginAt
) {
    public static UserRead from(User u) {
        return new UserRead(u.id(), u.email(), u.displayName(), u.avatarUrl(), u.platformAdmin(), u.active(), u.lastLoginAt());
    }
}
