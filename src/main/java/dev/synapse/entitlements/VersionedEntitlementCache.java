package dev.synapse.entitlements;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.cache.Caches;
import dev.synapse.core.cache.DeferredBumps;
import dev.synapse.core.cache.VersionedCache;
import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import dev.synapse.entitlements.EntitlementResolver.Overage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The reference's {@code VersionedCache("entl", ttl=60)}
 * ({@code entitlements/service.py}), over whatever cache backend is configured.
 *
 * <p>The body is written under the version observed at read time, and every
 * invalidation is BOTH immediate (so this request recomputes) and deferred to
 * after commit (so no concurrent request caches the pre-commit rows under the
 * new version for a whole TTL).
 */
@Component
public class VersionedEntitlementCache implements EntitlementCache {

    private static final Logger log = LoggerFactory.getLogger(VersionedEntitlementCache.class);

    /** Only the resolver's own output: the wire shape is private to this cache. */
    private record Snapshot(String org, String plan, String status, List<String> features, Map<String, LimitLine> limits) {}

    private record LimitLine(Long value, Double soft, Integer overageUnit, Long overagePriceCents) {}

    /** The (org, version) the last read on this thread observed, so {@link #put} writes under it. */
    private record Observed(UUID organizationId, long version) {}

    private final VersionedCache cache;
    private final ObjectMapper json;
    private final ThreadLocal<Observed> observed = new ThreadLocal<>();

    public VersionedEntitlementCache(Caches caches, ObjectMapper json) {
        this.cache = caches.entitlements();
        this.json = json;
    }

    @Override
    public Optional<EffectiveEntitlements> get(UUID organizationId) {
        VersionedCache.Versioned entry = cache.getVersioned(organizationId.toString());
        observed.set(new Observed(organizationId, entry.version()));
        if (entry.body() == null) {
            return Optional.empty();
        }
        try {
            Optional<EffectiveEntitlements> hit = Optional.of(fromSnapshot(json.readValue(entry.body(), Snapshot.class)));
            observed.remove();
            return hit;
        } catch (Exception e) {
            log.debug("entitlement_cache_corrupt org={} error={}", organizationId, e.toString());
            return Optional.empty(); // corrupt body ⇒ recompute under the version just read
        }
    }

    @Override
    public void put(UUID organizationId, EffectiveEntitlements effective) {
        Observed seen = observed.get();
        observed.remove();
        long version = seen != null && seen.organizationId().equals(organizationId)
            ? seen.version() : cache.currentVersion(organizationId.toString());
        try {
            cache.set(organizationId.toString(), json.writeValueAsString(toSnapshot(effective)), version);
        } catch (Exception e) {
            log.debug("entitlement_cache_write_failed org={} error={}", organizationId, e.toString());
        }
    }

    @Override
    public void invalidate(UUID organizationId) {
        cache.bump(organizationId.toString());                  // this request recomputes
        DeferredBumps.defer(cache, organizationId.toString());  // and again once the change is durable
    }

    private static Snapshot toSnapshot(EffectiveEntitlements effective) {
        Map<String, LimitLine> limits = new LinkedHashMap<>();
        effective.limits().forEach((metric, limit) -> limits.put(metric, new LimitLine(
            limit.value(), limit.softLimitRatio(),
            limit.overage() == null ? null : limit.overage().unit(),
            limit.overage() == null ? null : limit.overage().priceCents())));
        return new Snapshot(effective.organizationId().toString(), effective.planKey(), effective.subscriptionStatus(),
            effective.sortedFeatures(), limits);
    }

    private static EffectiveEntitlements fromSnapshot(Snapshot snapshot) {
        Map<String, Limit> limits = new LinkedHashMap<>();
        snapshot.limits().forEach((metric, line) -> limits.put(metric, new Limit(line.value(), line.soft(),
            line.overageUnit() == null ? null : new Overage(line.overageUnit(), line.overagePriceCents()))));
        return new EffectiveEntitlements(UUID.fromString(snapshot.org()), snapshot.plan(), snapshot.status(),
            Set.copyOf(snapshot.features()), limits);
    }
}
