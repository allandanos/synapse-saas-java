package dev.synapse.authorization;

import java.util.List;

/** A built-in role shared by every org ({@code roles.organization_id IS NULL}). */
public record SystemRole(String key, String name, String description, List<String> permissions) {}
