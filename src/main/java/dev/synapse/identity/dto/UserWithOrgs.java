package dev.synapse.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.synapse.identity.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserWithOrgs(
    UUID id,
    String email,
    String displayName,
    String avatarUrl,
    @JsonProperty("is_platform_admin") boolean platformAdmin,
    @JsonProperty("is_active") boolean active,
    Instant lastLoginAt,
    List<OrgSummary> orgs
) {
    public static UserWithOrgs from(User u, List<OrgSummary> orgs) {
        return new UserWithOrgs(u.id(), u.email(), u.displayName(), u.avatarUrl(), u.platformAdmin(), u.active(), u.lastLoginAt(), orgs);
    }
}
