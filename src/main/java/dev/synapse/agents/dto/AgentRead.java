package dev.synapse.agents.dto;

import dev.synapse.agents.Agent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentRead(UUID id, String slug, String name, String description, String status, Map<String, Object> config,
                        Instant createdAt, Instant updatedAt) {

    public static AgentRead from(Agent a) {
        return new AgentRead(a.id(), a.slug(), a.name(), a.description(), a.status(), a.config(), a.createdAt(), a.updatedAt());
    }
}
