package dev.synapse.core.web;

import java.util.UUID;

/** Feature-flag gate seam (implemented by {@code featureflags.FlagGate}). */
public interface FlagChecks {

    /** Throws 403 {@code permission_denied} ({@code reason: feature_flag_disabled}) unless the flag is on. */
    void require(String flagKey, UUID organizationId, UUID userId);
}
