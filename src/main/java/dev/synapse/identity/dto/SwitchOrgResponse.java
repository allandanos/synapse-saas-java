package dev.synapse.identity.dto;

/** The reference answers switch-org with an org-scoped access token only (200). */
public record SwitchOrgResponse(String accessToken, String tokenType, int expiresIn) {}
