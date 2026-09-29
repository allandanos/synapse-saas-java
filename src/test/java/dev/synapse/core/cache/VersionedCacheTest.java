package dev.synapse.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * VersionedCache correctness — the reference's {@code tests/unit/core/test_cache.py},
 * transliterated (the deferred bumps run on Spring's transaction synchronization
 * instead of the SQLAlchemy session).
 */
class VersionedCacheTest {

    private InProcessCacheBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InProcessCacheBackend();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private VersionedCache cache(String namespace) {
        return new VersionedCache(backend, namespace, 60);
    }

    @Nested
    @DisplayName("the version observed at read time")
    class VersionAtRead {

        @Test
        void setUsesTheVersionSeenAtRead() {
            VersionedCache cache = cache("t");
            VersionedCache.Versioned first = cache.getVersioned("k");
            assertThat(first.body()).isNull();
            assertThat(first.version()).isZero();

            cache.bump("k"); // a concurrent writer invalidated meanwhile
            cache.set("k", "stale-body", first.version());

            assertThat(cache.get("k")).isNull(); // the stale body sits under the old version only
            assertThat(cache.currentVersion("k")).isEqualTo(1);
        }

        @Test
        void setWithoutAVersionReadsAFreshOne() {
            VersionedCache cache = cache("t");
            cache.bump("k");
            cache.set("k", "v1-body");
            assertThat(cache.get("k")).isEqualTo("v1-body");
        }

        @Test
        void deleteIsABumpNotAReset() {
            VersionedCache cache = cache("t");
            cache.set("k", "under-v0");
            cache.bump("k");
            cache.set("k", "under-v1");
            cache.delete("k");
            // resetting to 0 would resurrect "under-v0"; a bump moves to v2 (empty)
            assertThat(cache.get("k")).isNull();
            assertThat(cache.currentVersion("k")).isEqualTo(2);
        }

        @Test
        void scopedBodiesMissWhenAnyScopeBumps() {
            VersionedCache cache = cache("flags");
            VersionedCache.Scoped miss = cache.getScoped("flag", "all", "org:o1", "user:u1");
            assertThat(miss.body()).isNull();
            cache.setScoped("flag", "1", miss.token());
            assertThat(cache.getScoped("flag", "all", "org:o1", "user:u1").body()).isEqualTo("1");
            // a different scope set never sees this body, even at identical versions
            assertThat(cache.getScoped("flag", "all", "org:o1", "user:u2").body()).isNull();

            cache.bump("user:u1");
            assertThat(cache.getScoped("flag", "all", "org:o1", "user:u1").body()).isNull();
            cache.setScoped("flag", "1", cache.getScoped("flag", "all", "org:o1", "user:u1").token());
            cache.bump("all");
            assertThat(cache.getScoped("flag", "all", "org:o1", "user:u1").body()).isNull();
        }

        @Test
        void aCorruptCounterBehavesLikeNeverBumped() {
            backend.set("t:ver:k", "not-a-number", 60);
            assertThat(cache("t").currentVersion("k")).isZero();
        }
    }

    @Nested
    @DisplayName("deferred bumps")
    class Deferred {

        @Test
        void deferredBumpRunsAfterCommit() {
            VersionedCache cache = cache("t");
            TransactionSynchronizationManager.initSynchronization();
            DeferredBumps.defer(cache, "k");
            DeferredBumps.defer(cache, "k"); // de-duplicated
            assertThat(cache.currentVersion("k")).isZero(); // nothing yet: the change is not durable

            commit();
            assertThat(cache.currentVersion("k")).isEqualTo(1);
        }

        @Test
        void rollbackDiscardsTheQueue() {
            VersionedCache cache = cache("t");
            TransactionSynchronizationManager.initSynchronization();
            DeferredBumps.defer(cache, "k");
            rollback();
            assertThat(cache.currentVersion("k")).isZero();
        }

        @Test
        void outsideATransactionTheBumpIsImmediate() {
            VersionedCache cache = cache("t");
            DeferredBumps.defer(cache, "k");
            assertThat(cache.currentVersion("k")).isEqualTo(1);
        }

        private void commit() {
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            finish(TransactionSynchronization.STATUS_COMMITTED);
        }

        private void rollback() {
            finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        private void finish(int status) {
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(status);
            }
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
