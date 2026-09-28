package dev.synapse.authorization.dto;

public record PermissionRead(String key, String resource, String action, String description) {}
