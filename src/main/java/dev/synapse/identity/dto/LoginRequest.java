package dev.synapse.identity.dto;

import dev.synapse.core.validation.EmailAddress;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(@NotNull @EmailAddress String email, @NotNull String password) {}
