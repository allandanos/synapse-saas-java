package dev.synapse.tenancy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Row of {@code memberships}; {@code permissionKeys} is the denormalised union of the member's roles. */
public record Membership(UUID id, UUID organizationId, UUID userId, String invitedEmail, String status, Instant joinedAt,
                         List<String> permissionKeys, LocalDateTime createdAt, String inviteTokenHash) {}
