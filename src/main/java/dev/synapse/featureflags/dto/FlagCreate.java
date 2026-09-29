package dev.synapse.featureflags.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FlagCreate(
    @NotNull @Pattern(regexp = "^[a-z0-9_.-]+$") @Size(min = 2, max = 100) String key,
    @NotNull @Size(min = 2, max = 200) String name,
    String description,
    Boolean enabled,
    @Min(0) @Max(100) Integer rolloutPercentage
) {
    public boolean enabledOrDefault() {
        return Boolean.TRUE.equals(enabled);
    }
}
