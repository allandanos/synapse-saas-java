package dev.synapse.identity.dto;

import dev.synapse.core.validation.EmailAddress;
import jakarta.validation.constraints.NotNull;

public record ForgotPasswordRequest(@NotNull @EmailAddress String email) {}
