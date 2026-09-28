package dev.synapse.identity.dto;

public record AuthResponse(UserRead user, TokenPair tokens) {}
