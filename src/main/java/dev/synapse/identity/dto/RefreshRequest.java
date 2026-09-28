package dev.synapse.identity.dto;

/** {@code refresh_token} is optional: the httpOnly cookie is the alternative carrier. */
public record RefreshRequest(String refreshToken) {}
