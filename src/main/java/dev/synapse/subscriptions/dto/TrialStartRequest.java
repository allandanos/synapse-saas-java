package dev.synapse.subscriptions.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TrialStartRequest(@NotNull @Size(min = 1) String planKey) {}
