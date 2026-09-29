package dev.synapse.featureflags.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Both fields optional; {@code null} leaves the stored value alone. */
public record FlagUpdate(Boolean enabled, @Min(0) @Max(100) Integer rolloutPercentage) {}
