package dev.synapse.tenancy.dto;

import jakarta.validation.constraints.Size;
import java.util.Map;

public record OrganizationUpdate(@Size(min = 2, max = 200) String name, Map<String, Object> settings) {}
