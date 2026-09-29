package dev.synapse.core.cache;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Post-commit cache invalidation (reference: {@code core/cache.py:defer_bump} +
 * {@code flush_deferred_bumps}, run by the request session in {@code core/db.py}).
 *
 * <p>Bumping inside the transaction lets a concurrent reader recompute from the
 * pre-commit rows and cache them under the NEW version — stale for a whole TTL
 * after an upgrade. Deferring to {@code afterCommit} closes that window; a
 * rollback drops the queue instead (nothing changed, nothing to invalidate).
 *
 * <p>With no transaction in progress (jobs, CLI) the bump happens immediately —
 * the caller has already committed.
 */
public final class DeferredBumps {

    private static final String RESOURCE_KEY = "synapse_deferred_bumps";

    private DeferredBumps() {}

    private record Pending(VersionedCache cache, String key) {}

    /** Queue {@code cache.bump(key)} for after the current transaction commits. */
    @SuppressWarnings("unchecked")
    public static void defer(VersionedCache cache, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.bump(key);
            return;
        }
        Set<Pending> pending = (Set<Pending>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (pending == null) {
            pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, pending);
            TransactionSynchronizationManager.registerSynchronization(new Flush());
        }
        pending.add(new Pending(cache, key)); // a set: the same bump queued twice runs once
    }

    private static final class Flush implements TransactionSynchronization {

        /** After the OpenFGA converge, so a bump is never undone by a later write. */
        public static final int ORDER = 200;

        @Override
        public int getOrder() {
            return ORDER;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void afterCommit() {
            Set<Pending> pending = (Set<Pending>) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
            if (pending != null) {
                pending.forEach(entry -> entry.cache().bump(entry.key()));
            }
        }

        @Override
        public void afterCompletion(int status) {
            if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                TransactionSynchronizationManager.unbindResource(RESOURCE_KEY); // rollback ⇒ the queue is dropped
            }
        }
    }
}
