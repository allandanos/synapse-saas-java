package dev.synapse.agents;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Row of {@code agents}: the org-scoped registry entry, not the runner
 * (ADR 0007 — this framework registers, gates, meters and bills agents; it
 * never executes them). {@code config} is opaque JSON owned by whichever
 * runtime executes the agent.
 */
public record Agent(UUID id, UUID organizationId, String slug, String name, String description, String status,
                    Map<String, Object> config, Instant deletedAt, Instant createdAt, Instant updatedAt) {

    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";

    public Agent {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public boolean isActive() {
        return ACTIVE.equals(status) && deletedAt == null;
    }
}
