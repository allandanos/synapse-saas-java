package dev.synapse.authorization;

import java.util.List;
import java.util.UUID;

/** Row of {@code roles} with its permission keys; {@code organizationId == null} ⇒ system role shared by all orgs. */
public record Role(UUID id, UUID organizationId, String key, String name, String description, boolean system, List<String> permissions) {}
