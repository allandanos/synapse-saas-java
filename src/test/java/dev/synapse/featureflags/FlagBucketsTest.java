package dev.synapse.featureflags;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Transliteration of the reference's {@code tests/unit/feature_flags/test_rollout.py}. */
class FlagBucketsTest {

    @Nested
    class Bucketing {

        @Test
        void bucketInRange() {
            for (int i = 0; i < 200; i++) {
                int bucket = FlagBuckets.bucketOf("flag", "user-" + i);
                assertThat(bucket).isBetween(0, FlagBuckets.BUCKETS - 1);
            }
        }

        @Test
        void deterministic() {
            assertThat(FlagBuckets.bucketOf("flag", "user-1")).isEqualTo(FlagBuckets.bucketOf("flag", "user-1"));
        }

        @Test
        void flagKeyChangesBucket() {
            // Not guaranteed for any single pair, but across many users the two flags must differ somewhere.
            assertThat(IntStream.range(0, 50).anyMatch(i -> FlagBuckets.bucketOf("a", "u" + i) != FlagBuckets.bucketOf("b", "u" + i)))
                .isTrue();
        }

        @Test
        void identifierChangesBucket() {
            int first = FlagBuckets.bucketOf("a", "u0");
            assertThat(IntStream.range(1, 50).anyMatch(i -> FlagBuckets.bucketOf("a", "u" + i) != first)).isTrue();
        }

        /** The exact digest slice the reference hashes — a drift here silently re-buckets every tenant. */
        @Test
        void matchesTheReferenceDigest() {
            assertThat(FlagBuckets.bucketOf("flag", "user-1")).isEqualTo(7346);
            assertThat(FlagBuckets.bucketOf("new-editor", "0f6dc7f6-2f2e-4ad0-9f5b-58e5c2e6f3a1")).isEqualTo(7063);
        }
    }

    @Nested
    class Rollout {

        @Test
        void zeroPercentOffForEveryone() {
            assertThat(IntStream.range(0, 100).anyMatch(i -> FlagBuckets.inRollout("f", "u" + i, 0))).isFalse();
        }

        @Test
        void hundredPercentOnForEveryone() {
            assertThat(IntStream.range(0, 100).allMatch(i -> FlagBuckets.inRollout("f", "u" + i, 100))).isTrue();
        }

        /** Raising the percentage can only add users, never remove them. */
        @Test
        void monotonic() {
            for (int i = 0; i < 60; i++) {
                String user = "u" + i;
                for (int pct = 0; pct < 100; pct += 10) {
                    if (FlagBuckets.inRollout("f", user, pct)) {
                        assertThat(FlagBuckets.inRollout("f", user, pct + 5)).isTrue();
                    }
                }
            }
        }

        @Test
        void approximateDistribution() {
            long enabled = IntStream.range(0, 1000).filter(i -> FlagBuckets.inRollout("f", "u" + i, 50)).count();
            assertThat(enabled).isBetween(400L, 600L);
        }

        /** The same user resolves the same way on every check — no flapping. */
        @Test
        void stableMembership() {
            Set<Boolean> results = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                results.add(FlagBuckets.inRollout("f", "u-42", 50));
            }
            assertThat(results).hasSize(1);
        }
    }
}
