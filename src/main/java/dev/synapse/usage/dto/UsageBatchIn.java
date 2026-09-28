package dev.synapse.usage.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UsageBatchIn(@NotNull @Size(min = 1, max = 100) List<@Valid UsageEventIn> events) {}
