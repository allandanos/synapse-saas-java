package dev.synapse.identity.dto;

import dev.synapse.core.validation.EmailAddress;
import dev.synapse.core.validation.PasswordPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotNull @EmailAddress String email,
    @NotNull @PasswordPolicy String password,
    @NotNull @Size(min = 1, max = 200) String displayName
) {}
