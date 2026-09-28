package dev.synapse.authorization.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record RoleUpdate(@Size(min = 2, max = 200) String name, String description, List<String> permissions) {}
