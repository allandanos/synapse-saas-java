package dev.synapse.featureflags;

import dev.synapse.core.errors.PermissionDeniedError;
import dev.synapse.core.web.FlagChecks;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The router-facing flag gate behind {@code @RequireFlag} (reference: {@code feature_flags/dependencies.py}). */
@Component
public class FlagGate implements FlagChecks {

    private final FeatureFlagService flags;

    public FlagGate(FeatureFlagService flags) {
        this.flags = flags;
    }

    @Override
    public void require(String flagKey, UUID organizationId, UUID userId) {
        if (flags.isEnabled(flagKey, organizationId, userId)) {
            return;
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("flag", flagKey);
        extras.put("reason", "feature_flag_disabled");
        throw new PermissionDeniedError("This action requires the '" + flagKey + "' feature flag to be enabled", extras);
    }
}
