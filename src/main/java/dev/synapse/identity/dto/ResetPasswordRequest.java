package dev.synapse.identity.dto;

import dev.synapse.core.validation.PasswordPolicy;
import jakarta.validation.constraints.NotNull;

public record ResetPasswordRequest(@NotNull String token, @NotNull @PasswordPolicy String password) {}
