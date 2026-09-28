package dev.synapse.entitlements.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The operator's grant body. {@code enabled=false} is the kill switch: a high-priority grant that REMOVES the feature. */
public record GrantRequest(
    @NotNull @Size(min = 1) String featureKey,
    @NotNull @Pattern(regexp = "^(trial|addon|promo|beta|override|enterprise|grandfather)$") String source,
    Boolean enabled,
    @Min(1) @Max(3650) Integer durationDays,
    String note,
    @Min(0) Long limitValue
) {
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
