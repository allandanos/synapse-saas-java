package dev.synapse.identity.dto;

import java.util.UUID;

public record InviteAcceptResponse(UUID organizationId, String status) {}
