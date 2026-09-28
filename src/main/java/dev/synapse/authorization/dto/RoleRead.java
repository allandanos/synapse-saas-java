package dev.synapse.authorization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.synapse.authorization.Role;
import java.util.List;
import java.util.UUID;

public record RoleRead(UUID id, String key, String name, String description, @JsonProperty("is_system") boolean system, List<String> permissions) {

    public static RoleRead from(Role r) {
        return new RoleRead(r.id(), r.key(), r.name(), r.description(), r.system(), r.permissions());
    }
}
