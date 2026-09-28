package dev.synapse.identity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** Row of {@code users}. {@code passwordHash == null} ⇒ SSO-only account (no local password). */
public record User(
    UUID id,
    String email,
    String passwordHash,
    String displayName,
    String avatarUrl,
    boolean platformAdmin,
    boolean active,
    Instant lastLoginAt,
    String identityProvider,
    String providerSubject,
    LocalDateTime createdAt
) {}
