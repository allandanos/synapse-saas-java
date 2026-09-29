package dev.synapse.agents.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record AgentCreate(
    @NotNull @Pattern(regexp = "^[a-z0-9][a-z0-9-]*$") @Size(min = 2, max = 100) String slug,
    @NotNull @Size(min = 2, max = 200) String name,
    String description,
    Map<String, Object> config
) {
    public Map<String, Object> configOrEmpty() {
        return config == null ? Map.of() : config;
    }
}
