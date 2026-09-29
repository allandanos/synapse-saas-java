package dev.synapse.featureflags;

import dev.synapse.core.cache.Caches;
import dev.synapse.core.cache.DeferredBumps;
import dev.synapse.core.cache.VersionedCache;
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
 * <p>Resolution is memoised under the (global, org, user) scope versions, so
 * any of the three invalidations — a flag edit, an org override, a user
 * override — misses correctly (reference: {@code VersionedCache("fflags", ttl=30)}).
 */
@Service
public class FeatureFlagService {

    /** The global scope: a flag edit invalidates every evaluation at once. */
    public static final String GLOBAL_SCOPE = "all";

    private final FeatureFlagRepository flags;
    private final VersionedCache cache;

    public FeatureFlagService(FeatureFlagRepository flags, Caches caches) {
        this.flags = flags;
        this.cache = caches.flags();
    }

    // ── Resolution ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean isEnabled(String flagKey, UUID organizationId, UUID userId) {
        String cacheKey = flagKey + "|" + organizationId + "|" + userId;
        VersionedCache.Scoped cached = cache.getScoped(cacheKey, GLOBAL_SCOPE, "org:" + organizationId, "user:" + userId);
        if (cached.body() != null) {
            return "1".equals(cached.body());
        }
        boolean enabled = evaluate(flagKey, organizationId, userId);
        cache.setScoped(cacheKey, enabled ? "1" : "0", cached.token());
        return enabled;
    }

    private boolean evaluate(String flagKey, UUID organizationId, UUID userId) {
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
        FeatureFlag updated = flags.updateFlag(key,
            enabled != null ? enabled : flag.enabled(),
            rolloutPercentage != null ? rolloutPercentage : flag.rolloutPercentage());
        invalidate(GLOBAL_SCOPE);
        return updated;
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
        FeatureFlagOverride result = existing != null
            ? flags.updateOverride(existing.id(), enabled, note)
            : flags.insertOverride(flagKey, organizationId, userId, enabled, note);
        bumpScope(organizationId, userId);
        return result;
    }

    @Transactional
    public void deleteOverride(UUID overrideId) {
        FeatureFlagOverride override = flags.findOverrideById(overrideId)
            .orElseThrow(() -> new FeatureFlagNotFoundError("Override not found"));
        flags.deleteOverride(overrideId);
        bumpScope(override.organizationId(), override.userId());
    }

    private void bumpScope(UUID organizationId, UUID userId) {
        if (organizationId != null) {
            invalidate("org:" + organizationId);
        }
        if (userId != null) {
            invalidate("user:" + userId);
        }
    }

    /** Now (this request sees the change) and after commit (nobody caches pre-commit rows). */
    private void invalidate(String scope) {
        cache.bump(scope);
        DeferredBumps.defer(cache, scope);
    }

    private FeatureFlag requireFlag(String key) {
        return flags.findByKey(key).orElseThrow(() -> new FeatureFlagNotFoundError("Flag '" + key + "' not found"));
    }
}
