package dev.synapse.entitlements.dto;

import java.util.UUID;

public record GrantCreated(UUID id, String featureKey, String source) {}
