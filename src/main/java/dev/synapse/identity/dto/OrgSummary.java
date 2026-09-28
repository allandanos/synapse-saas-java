package dev.synapse.identity.dto;

import java.util.List;
import java.util.UUID;

public record OrgSummary(UUID id, String slug, String name, List<String> roleKeys) {}
