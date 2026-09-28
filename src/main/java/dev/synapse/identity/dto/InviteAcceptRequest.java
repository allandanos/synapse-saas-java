package dev.synapse.identity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(@NotNull @Size(min = 10) String token) {}
