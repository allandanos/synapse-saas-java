package dev.synapse.core.audit;

import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.UserContext;
import dev.synapse.core.db.Json;
import dev.synapse.core.ids.Ids;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One call, one immutable {@code audit_logs} row, same transaction as the change.
 *
 * <p>Actor resolution: an explicit actor wins; otherwise the bound principal. A
 * programmatic principal (API key) is attributed to the human who created the
 * key with {@code actor_type = api_key} and the key id in the diff — its
 * sentinel user id is never written to the FK column.
 */
@Component
public class AuditService {

    private final JdbcClient jdbc;
    private final Json json;

    public AuditService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void log(String eventType, UUID organizationId) {
        log(eventType, organizationId, null, null, null, null);
    }

    public void log(String eventType, UUID organizationId, UUID actorUserId, String targetType, UUID targetId, Map<String, ?> diff) {
        UUID actor = actorUserId;
        String actorType = "user";
        Map<String, Object> effectiveDiff = diff == null ? null : new LinkedHashMap<>(diff);
        UserContext user = RequestContextHolder.currentUser();
        if (actor == null && user != null) {
            if (user.isApiKey()) {
                actor = user.apiKeyCreatorId();
                actorType = "api_key";
                effectiveDiff = effectiveDiff == null ? new LinkedHashMap<>() : effectiveDiff;
                effectiveDiff.put("api_key_id", user.apiKeyId().toString());
            } else {
                actor = user.userId();
            }
        }
        if (actor == null && "user".equals(actorType)) {
            actorType = "system";
        }
        jdbc.sql("""
                INSERT INTO audit_logs (id, organization_id, actor_user_id, actor_type, event_type, target_type, target_id, diff, request_id)
                VALUES (:id, :organizationId, :actorUserId, :actorType, :eventType, :targetType, :targetId, CAST(:diff AS jsonb), :requestId)
                """)
            .param("id", Ids.uuidV7())
            .param("organizationId", organizationId)
            .param("actorUserId", actor)
            .param("actorType", actorType)
            .param("eventType", eventType)
            .param("targetType", targetType)
            .param("targetId", targetId)
            .param("diff", effectiveDiff == null ? null : json.write(effectiveDiff))
            .param("requestId", RequestContextHolder.requestId())
            .update();
    }
}
