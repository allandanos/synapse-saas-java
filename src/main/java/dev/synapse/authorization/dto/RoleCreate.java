package dev.synapse.authorization.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RoleCreate(
    @NotNull @Pattern(regexp = "^[a-z0-9_]+$") @Size(min = 2, max = 64) String key,
    @NotNull @Size(min = 2, max = 200) String name,
    String description,
    @NotNull List<String> permissions
) {}
