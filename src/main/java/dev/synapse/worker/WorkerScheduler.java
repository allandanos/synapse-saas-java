package dev.synapse.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The worker's cadences (reference: {@code worker/jobs.py:build_cron_jobs}),
 * running in-process with the API unless {@code SYNAPSE_WORKER_ENABLED=false}.
 * The same class is the standalone worker's body — {@code --worker} just starts
 * the app without the web server.
 *
 * <p>Every tick goes through {@link JobRegistry}, so it takes the job's advisory
 * lock first and does nothing when another worker already holds it. A failing
 * job logs and lets the next tick retry; it never stops the scheduler.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "synapse.worker-enabled", havingValue = "true", matchIfMissing = true)
public class WorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerScheduler.class);

    private final JobRegistry jobs;

    public WorkerScheduler(JobRegistry jobs) {
        this.jobs = jobs;
    }

    /** Every 5 seconds. */
    @Scheduled(cron = "*/5 * * * * *")
    public void dispatchOutbox() {
        tick("dispatch_outbox");
    }

    /** Every 15 seconds. */
    @Scheduled(cron = "*/15 * * * * *")
    public void deliverWebhooks() {
        tick("deliver_webhooks");
    }

    /** Hourly at :05. */
    @Scheduled(cron = "0 5 * * * *")
    public void rollupUsage() {
        tick("rollup_usage");
    }

    /** Hourly at :10. */
    @Scheduled(cron = "0 10 * * * *")
    public void expireEntitlements() {
        tick("expire_entitlements");
    }

    /** Hourly at :20. */
    @Scheduled(cron = "0 20 * * * *")
    public void advanceRecurringBilling() {
        tick("advance_recurring_billing");
    }

    /** Daily at 03:30. */
    @Scheduled(cron = "0 30 3 * * *")
    public void ensurePartitions() {
        tick("ensure_partitions");
    }

    /** Daily at 03:40. */
    @Scheduled(cron = "0 40 3 * * *")
    public void purgeExpired() {
        tick("purge_expired");
    }

    private void tick(String job) {
        try {
            jobs.run(job);
        } catch (RuntimeException e) {
            log.error("job_failed job={} error={}", job, e.toString(), e);
        }
    }
}
