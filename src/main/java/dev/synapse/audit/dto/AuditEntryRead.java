package dev.synapse.audit.dto;

import dev.synapse.audit.AuditEntry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEntryRead(UUID id, UUID organizationId, UUID actorUserId, String actorType, String eventType,
                             String targetType, UUID targetId, Map<String, Object> diff, String requestId, Instant createdAt) {

    public static AuditEntryRead from(AuditEntry e) {
        return new AuditEntryRead(e.id(), e.organizationId(), e.actorUserId(), e.actorType(), e.eventType(), e.targetType(),
            e.targetId(), e.diff(), e.requestId(), e.createdAt());
    }
}
