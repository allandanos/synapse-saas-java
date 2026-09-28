package dev.synapse.authorization;

/** One catalog entry: {@code resource:action}. */
public record PermissionDef(String key, String resource, String action, String description) {}
