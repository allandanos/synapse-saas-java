package dev.synapse.tenancy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizationCreate(
    @NotNull @Size(min = 2, max = 200) String name,
    @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{0,46}[a-z0-9])?$") String slug
) {}
