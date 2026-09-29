package dev.synapse.featureflags;

import dev.synapse.core.errors.ConflictError;
import dev.synapse.core.errors.FeatureFlagNotFoundError;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature flag resolution and management (reference: {@code feature_flags/service.py}).
 *
 * <p>Resolution order, first match wins: user override → org override →
 * global default. A global default with a {@code rollout_percentage} becomes a
 * deterministic bucket test (see {@link FlagBuckets}) over the user id when
 * there is one, else the org id. Unknown flags are off — new code paths stay
 * dark by default.
 *
 * <p>The reference memoises resolution in a version-counter cache; this port
 * reads through to Postgres (same answers, one fewer moving part).
 */
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository flags;

    public FeatureFlagService(FeatureFlagRepository flags) {
        this.flags = flags;
    }

    // ── Resolution ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean isEnabled(String flagKey, UUID organizationId, UUID userId) {
        FeatureFlag flag = flags.findByKey(flagKey).orElse(null);
        if (flag == null) {
            return false; // unknown flags are off
        }
        if (userId != null) {
            FeatureFlagOverride override = flags.findOverride(flagKey, null, userId).orElse(null);
            if (override != null) {
                return override.enabled();
            }
        }
        if (organizationId != null) {
            FeatureFlagOverride override = flags.findOverride(flagKey, organizationId, null).orElse(null);
            if (override != null) {
                return override.enabled();
            }
        }
        if (flag.rolloutPercentage() != null) {
            String identifier = userId != null ? userId.toString()
                : organizationId != null ? organizationId.toString() : "anonymous";
            return FlagBuckets.inRollout(flagKey, identifier, flag.rolloutPercentage());
        }
        return flag.enabled();
    }

    // ── Management (platform-admin surface) ──────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FeatureFlag> listFlags() {
        return flags.listFlags();
    }

    @Transactional
    public FeatureFlag createFlag(String key, String name, String description, boolean enabled, Integer rolloutPercentage) {
        if (flags.findByKey(key).isPresent()) {
            throw new ConflictError("Flag '" + key + "' already exists", Map.of("key", key));
        }
        return flags.insertFlag(key, name, description, enabled, rolloutPercentage);
    }

    @Transactional
    public FeatureFlag updateFlag(String key, Boolean enabled, Integer rolloutPercentage) {
        FeatureFlag flag = requireFlag(key);
        return flags.updateFlag(key,
            enabled != null ? enabled : flag.enabled(),
            rolloutPercentage != null ? rolloutPercentage : flag.rolloutPercentage());
    }

    @Transactional(readOnly = true)
    public List<FeatureFlagOverride> listOverrides(String flagKey) {
        return flags.listOverrides(flagKey);
    }

    /** Upsert: one override per (flag, scope); re-posting the same scope edits it in place. */
    @Transactional
    public FeatureFlagOverride setOverride(String flagKey, UUID organizationId, UUID userId, boolean enabled, String note) {
        requireFlag(flagKey);
        if (organizationId == null && userId == null) {
            throw new FeatureFlagNotFoundError("Override requires an organization_id or user_id");
        }
        FeatureFlagOverride existing = flags.findOverride(flagKey, organizationId, userId).orElse(null);
        if (existing != null) {
            return flags.updateOverride(existing.id(), enabled, note);
        }
        return flags.insertOverride(flagKey, organizationId, userId, enabled, note);
    }

    @Transactional
    public void deleteOverride(UUID overrideId) {
        if (flags.findOverrideById(overrideId).isEmpty()) {
            throw new FeatureFlagNotFoundError("Override not found");
        }
        flags.deleteOverride(overrideId);
    }

    private FeatureFlag requireFlag(String key) {
        return flags.findByKey(key).orElseThrow(() -> new FeatureFlagNotFoundError("Flag '" + key + "' not found"));
    }
}
