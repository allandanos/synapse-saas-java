package dev.synapse.usage;

import dev.synapse.core.db.Json;
import dev.synapse.core.errors.UnknownMetricError;
import dev.synapse.core.errors.UsageLimitExceededError;
import dev.synapse.core.errors.ValidationFailedError;
import dev.synapse.core.ids.Ids;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import dev.synapse.entitlements.EntitlementService;
import dev.synapse.subscriptions.Metric;
import dev.synapse.subscriptions.MetricRepository;
import dev.synapse.usage.dto.UsageCheckOut;
import dev.synapse.usage.dto.UsageEventIn;
import dev.synapse.usage.dto.UsageResultOut;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usage metering (reference: {@code usage/service.py}).
 *
 * <ul>
 *   <li>{@link #record}: metering never blocks — always succeeds (soft analytics path)</li>
 *   <li>{@link #check}: read-only pre-flight against the effective limit</li>
 *   <li>{@link #consume}: atomic increment + limit compare; a breach rolls the whole
 *       transaction back (event + counter + idempotency reservation) and raises 402</li>
 *   <li>gauges are levels in a fixed bucket; a positive delta is capacity-checked</li>
 * </ul>
 * Counter increments are one {@code INSERT … ON CONFLICT DO UPDATE … RETURNING}
 * so concurrent consumers cannot overshoot without the breach being detected.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    /** Gauges live in one fixed period bucket: a level has no month to reset with. */
    public static final LocalDate GAUGE_PERIOD = LocalDate.of(1970, 1, 1);
    public static final String UPGRADE_URL = "/dashboard/billing";
    public static final String API_REQUESTS_METRIC = "api_requests";
    public static final String SEATS_METRIC = "users";

    private final UsageRepository usage;
    private final MetricRepository metrics;
    private final EntitlementService entitlements;
    private final OutboxWriter outbox;
    private final FrameworkMetrics counters;
    private final Json json;

    public UsageService(UsageRepository usage, MetricRepository metrics, EntitlementService entitlements, OutboxWriter outbox,
                        FrameworkMetrics counters, Json json) {
        this.usage = usage;
        this.metrics = metrics;
        this.entitlements = entitlements;
        this.outbox = outbox;
        this.counters = counters;
        this.json = json;
    }

    // ── Reads ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public long currentTotal(UUID organizationId, String metric) {
        return usage.total(organizationId, metric, periodFor(metric));
    }

    /** {@code [{metric, used}]} for the month (plus the gauge bucket); only metered metrics appear. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> summary(UUID organizationId, LocalDate period) {
        LocalDate periodStart = period != null ? period : monthBucket(Instant.now());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UsageRepository.CounterRow row : usage.summary(organizationId, periodStart, GAUGE_PERIOD)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("metric", row.metric());
            entry.put("used", row.quantityTotal());
            rows.add(entry);
        }
        return rows;
    }

    /** Read-only limit check for pre-flight UI. */
    @Transactional(readOnly = true)
    public UsageCheckOut check(UUID organizationId, String metric, long quantity) {
        EffectiveEntitlements effective = entitlements.effectiveForOrg(organizationId);
        long used = currentTotal(organizationId, metric);
        return checkAgainst(effective, metric, used, quantity);
    }

    /** Pure limit arithmetic for an already-resolved entitlement set. */
    public static UsageCheckOut checkAgainst(EffectiveEntitlements effective, String metric, long used, long quantity) {
        Limit limit = effective.limit(metric);
        Long value = limit == null ? null : limit.value();
        // int(value * ratio) — only when the limit, its value and its ratio are all "truthy"
        Long soft = limit != null && value != null && value != 0 && limit.softLimitRatio() != null && limit.softLimitRatio() != 0.0
            ? (long) (value * limit.softLimitRatio()) : null;
        return new UsageCheckOut(metric, used, value, value == null ? null : value - used, value == null || used + quantity <= value,
            soft, soft != null && used >= soft);
    }

    // ── Writes ───────────────────────────────────────────────────────────────────

    /** Record a usage event. Metering never blocks; no limit enforcement. */
    @Transactional
    public UsageResultOut record(UUID organizationId, String metric, long quantity, Instant occurredAt, String idempotencyKey, Map<String, Object> properties) {
        assertCounterMetric(metric);
        if (idempotencyKey != null && !usage.reserve(organizationId, idempotencyKey, metric, quantity)) {
            return replay(organizationId, idempotencyKey);
        }
        Instant now = occurredAt != null ? occurredAt : Instant.now();
        UUID eventId = usage.insertEvent(organizationId, metric, quantity, now, idempotencyKey, json.write(properties == null ? Map.of() : properties));
        long total = usage.increment(organizationId, metric, quantity, monthBucket(now), now);
        maybeEmitSoftLimit(organizationId, metric, total);
        if (idempotencyKey != null) {
            usage.settle(organizationId, idempotencyKey, eventId, total);
        }
        return UsageResultOut.recorded(metric, quantity, total);
    }

    /** {@code POST /usage/events}: every event in one transaction (an unknown metric rejects the whole batch). */
    @Transactional
    public List<UsageResultOut> recordMany(UUID organizationId, List<UsageEventIn> events) {
        List<UsageResultOut> results = new ArrayList<>();
        for (UsageEventIn event : events) {
            results.add(record(organizationId, event.metric(), event.quantityOrDefault(), null, event.idempotencyKey(), event.properties()));
        }
        return results;
    }

    /**
     * Record + enforce. A breach throws {@link UsageLimitExceededError} (402) and the
     * transaction (event + counter + idempotency reservation) rolls back together.
     */
    @Transactional
    public UsageResultOut consume(UUID organizationId, String metric, long quantity, String idempotencyKey, Map<String, Object> properties) {
        assertCounterMetric(metric);
        Instant now = Instant.now();

        EffectiveEntitlements effective = entitlements.effectiveForOrg(organizationId);
        Limit limit = effective.limit(metric);
        Long limitValue = limit == null ? null : limit.value();

        if (idempotencyKey != null && !usage.reserve(organizationId, idempotencyKey, metric, quantity)) {
            UsageResultOut replay = replay(organizationId, idempotencyKey);
            return UsageResultOut.enforced(replay.metric(), replay.quantity(), replay.total(), limitValue, true);
        }

        UUID eventId = usage.insertEvent(organizationId, metric, quantity, now, idempotencyKey, json.write(properties == null ? Map.of() : properties));
        long total = usage.increment(organizationId, metric, quantity, monthBucket(now), now);

        if (limitValue != null && total > limitValue) {
            counters.usageLimited(metric);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("metric", metric);
            extras.put("limit", limitValue);
            extras.put("used", total - quantity);
            extras.put("attempted", quantity);
            extras.put("upgrade_url", UPGRADE_URL);
            throw new UsageLimitExceededError(metric + " limit exceeded (" + limitValue + "/period)", extras);
        }

        maybeEmitSoftLimit(organizationId, metric, total);
        if (limitValue != null && total >= limitValue) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("metric", metric);
            payload.put("limit", limitValue);
            payload.put("total", total);
            outbox.append(Events.USAGE_HARD_LIMIT_REACHED, "usage", Ids.uuidV7(), organizationId, payload);
        }
        if (idempotencyKey != null) {
            usage.settle(organizationId, idempotencyKey, eventId, total);
        }
        return UsageResultOut.enforced(metric, quantity, total, limitValue, false);
    }

    /** Consume a batch atomically: the first breach throws and the transaction rolls back every event (all-or-nothing). */
    @Transactional
    public List<UsageResultOut> consumeMany(UUID organizationId, List<UsageEventIn> events) {
        List<UsageResultOut> results = new ArrayList<>();
        for (UsageEventIn event : events) {
            results.add(consume(organizationId, event.metric(), event.quantityOrDefault(), event.idempotencyKey(), event.properties()));
        }
        return results;
    }

    // ── Gauges ───────────────────────────────────────────────────────────────────

    /** Set a gauge to an absolute level (seats in use, projects, bytes stored). A sync, never refused. */
    @Transactional
    public UsageResultOut setGauge(UUID organizationId, String metric, long value) {
        assertGaugeMetric(metric);
        long level = Math.max(value, 0);
        usage.setLevel(organizationId, metric, GAUGE_PERIOD, level);
        return gaugeResult(organizationId, metric, level);
    }

    /**
     * Move a gauge by {@code delta} (never below zero); returns the new level. A
     * positive delta is capacity-checked first (402 with upgrade hints when it
     * would exceed the limit) unless {@code enforce} is off.
     */
    @Transactional
    public UsageResultOut adjustGauge(UUID organizationId, String metric, long delta, boolean enforce) {
        assertGaugeMetric(metric);
        if (enforce && delta > 0) {
            long current = currentTotal(organizationId, metric);
            ensureGaugeCapacity(organizationId, metric, current, delta);
        }
        long level = usage.adjustLevel(organizationId, metric, GAUGE_PERIOD, delta);
        return gaugeResult(organizationId, metric, level);
    }

    /** Gauge (capacity) check — e.g. seats. The caller holds the row lock. */
    @Transactional(readOnly = true)
    public void ensureGaugeCapacity(UUID organizationId, String metric, long current, long adding) {
        assertGaugeMetric(metric);
        Long value = entitlements.effectiveForOrg(organizationId).limitValue(metric);
        if (value != null && current + adding > value) {
            throw new UsageLimitExceededError(metric + " limit reached (" + value + ")", limitExtras(metric, value, current));
        }
    }

    public static Map<String, Object> limitExtras(String metric, long limit, long used) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("metric", metric);
        extras.put("limit", limit);
        extras.put("used", used);
        extras.put("upgrade_url", UPGRADE_URL);
        return extras;
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private UsageResultOut gaugeResult(UUID organizationId, String metric, long level) {
        Long value = entitlements.effectiveForOrg(organizationId).limitValue(metric);
        return UsageResultOut.enforced(metric, level, level, value, false);
    }

    private Metric metric(String key) {
        return metrics.findByKey(key).orElseThrow(() -> new UnknownMetricError("Unknown usage metric '" + key + "'", Map.of("metric", key)));
    }

    private void assertCounterMetric(String key) {
        if (metric(key).isGauge()) {
            throw new ValidationFailedError("'" + key + "' is a gauge (a level, not a flow): set it with POST /usage/gauge",
                Map.of("metric", key, "kind", "gauge"));
        }
    }

    private void assertGaugeMetric(String key) {
        Metric row = metric(key);
        if (!row.isGauge()) {
            throw new ValidationFailedError("'" + key + "' is a counter: meter it with /usage/events or /usage/consume",
                Map.of("metric", key, "kind", row.kind()));
        }
    }

    private LocalDate periodFor(String key) {
        return metric(key).isGauge() ? GAUGE_PERIOD : monthBucket(Instant.now());
    }

    private UsageResultOut replay(UUID organizationId, String key) {
        UsageRepository.IdempotencyRow row = usage.replay(organizationId, key).orElseThrow();
        log.info("usage_deduplicated org={} idempotency_key={} metric={}", organizationId, key, row.metric());
        return UsageResultOut.deduplicated(row.metric(), row.quantity(), row.totalAfter() == null ? 0 : row.totalAfter());
    }

    /** Emit {@code usage.soft_limit_reached} exactly once per metric per period. */
    private void maybeEmitSoftLimit(UUID organizationId, String metric, long total) {
        Limit limit = entitlements.effectiveForOrg(organizationId).limit(metric);
        if (limit == null || limit.value() == null || limit.softLimitRatio() == null || limit.softLimitRatio() == 0.0) {
            return;
        }
        long threshold = (long) (limit.value() * limit.softLimitRatio());
        if (total < threshold) {
            return;
        }
        if (usage.markSoftLimitNotified(organizationId, metric, monthBucket(Instant.now()))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("organization_id", organizationId.toString());
            payload.put("metric", metric);
            payload.put("threshold", threshold);
            payload.put("total", total);
            payload.put("limit", limit.value());
            outbox.append(Events.USAGE_SOFT_LIMIT_REACHED, "usage", Ids.uuidV7(), organizationId, payload);
        }
    }

    /** UTC month bucket, e.g. 2026-09-01. */
    public static LocalDate monthBucket(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }
}
