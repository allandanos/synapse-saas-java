package dev.synapse.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Row of {@code audit_logs} as the API exposes it (ip/user_agent stay internal). */
public record AuditEntry(UUID id, UUID organizationId, UUID actorUserId, String actorType, String eventType,
                         String targetType, UUID targetId, Map<String, Object> diff, String requestId, Instant createdAt) {}
