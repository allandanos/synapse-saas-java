package dev.synapse.usage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementResolver.Limit;
import dev.synapse.usage.dto.UsageCheckOut;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code UsageService.checkAgainst}: the pure limit arithmetic behind {@code /usage/check} and {@code /usage/summary}. */
class UsageChecksTest {

    static EffectiveEntitlements entitlements(Map<String, Limit> limits) {
        return new EffectiveEntitlements(UUID.randomUUID(), "free", "active", Set.of(), limits);
    }

    @Test
    void withinTheLimitWithSoftThreshold() {
        UsageCheckOut check = UsageService.checkAgainst(entitlements(Map.of("api_requests", Limit.of(10_000L, 0.8))), "api_requests", 7_999, 1);
        assertThat(check).isEqualTo(new UsageCheckOut("api_requests", 7_999, 10_000L, 2_001L, true, 8_000L, false));
    }

    @Test
    void softLimitBreachedAtTheThreshold() {
        UsageCheckOut check = UsageService.checkAgainst(entitlements(Map.of("api_requests", Limit.of(10_000L, 0.8))), "api_requests", 8_000, 1);
        assertThat(check.softLimitBreached()).isTrue();
        assertThat(check.withinLimit()).isTrue();
    }

    @Test
    void quantityCountsAgainstTheRemainder() {
        UsageCheckOut check = UsageService.checkAgainst(entitlements(Map.of("api_requests", Limit.of(10L, null))), "api_requests", 8, 3);
        assertThat(check.withinLimit()).isFalse();
        assertThat(check.remaining()).isEqualTo(2L);
        assertThat(check.softLimit()).isNull();
        assertThat(check.softLimitBreached()).isFalse();
    }

    @Test
    void exactlyAtTheLimitIsStillWithin() {
        assertThat(UsageService.checkAgainst(entitlements(Map.of("m", Limit.of(10L, null))), "m", 9, 1).withinLimit()).isTrue();
        assertThat(UsageService.checkAgainst(entitlements(Map.of("m", Limit.of(10L, null))), "m", 10, 1).withinLimit()).isFalse();
    }

    @Test
    void unlimitedAndUnknownMetricsNeverBreach() {
        UsageCheckOut unlimited = UsageService.checkAgainst(entitlements(Map.of("storage_bytes", Limit.of(null, 0.8))), "storage_bytes", 1L << 40, 1);
        assertThat(unlimited).isEqualTo(new UsageCheckOut("storage_bytes", 1L << 40, null, null, true, null, false));
        UsageCheckOut unknown = UsageService.checkAgainst(entitlements(Map.of()), "ai_tokens", 5, 1);
        assertThat(unknown.limit()).isNull();
        assertThat(unknown.withinLimit()).isTrue();
    }

    @Test
    void zeroLimitOrZeroRatioHasNoSoftThreshold() {
        assertThat(UsageService.checkAgainst(entitlements(Map.of("m", Limit.of(0L, 0.8))), "m", 0, 1).softLimit()).isNull();
        assertThat(UsageService.checkAgainst(entitlements(Map.of("m", Limit.of(100L, 0.0))), "m", 0, 1).softLimit()).isNull();
    }

    @Test
    void softThresholdTruncatesLikePythonInt() {
        assertThat(UsageService.checkAgainst(entitlements(Map.of("m", Limit.of(7L, 0.5))), "m", 0, 1).softLimit()).isEqualTo(3L);
    }

    @Test
    void monthBucketIsTheFirstOfTheUtcMonth() {
        assertThat(UsageService.monthBucket(Instant.parse("2026-09-30T23:30:00Z"))).isEqualTo(java.time.LocalDate.of(2026, 9, 1));
        assertThat(UsageService.monthBucket(Instant.parse("2026-10-01T00:00:00Z"))).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
    }
}
