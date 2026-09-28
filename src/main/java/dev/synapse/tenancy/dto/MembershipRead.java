package dev.synapse.tenancy.dto;

import dev.synapse.tenancy.MembershipView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record MembershipRead(UUID id, UUID organizationId, UUID userId, String invitedEmail, String email, String displayName,
                             String status, Instant joinedAt, List<String> roleKeys, LocalDateTime createdAt) {

    public static MembershipRead from(MembershipView v) {
        var m = v.membership();
        return new MembershipRead(m.id(), m.organizationId(), m.userId(), m.invitedEmail(), v.email(), v.displayName(),
            m.status(), m.joinedAt(), v.roleKeys(), m.createdAt());
    }
}
