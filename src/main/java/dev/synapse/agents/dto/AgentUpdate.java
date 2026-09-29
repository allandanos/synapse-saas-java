package dev.synapse.agents.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

/** Every field is optional; {@code null} leaves the stored value alone. */
public record AgentUpdate(
    @Size(min = 2, max = 200) String name,
    String description,
    Map<String, Object> config
) {}
