package dev.synapse.worker;

import dev.synapse.core.metrics.FrameworkMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The worker's job table, in dispatch order (reference:
 * {@code cli.py:JOB_NAMES}). Every run takes the job's advisory lock and
 * records a Micrometer outcome; metrics must never break a job.
 */
@Component
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    /** Dispatch order — what {@code --jobs-run-once --all} executes. */
    public static final List<String> JOB_NAMES = List.of(
        "dispatch_outbox", "deliver_webhooks", "rollup_usage", "expire_entitlements",
        "advance_recurring_billing", "ensure_partitions", "purge_expired");

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final AdvisoryLock lock;
    private final FrameworkMetrics metrics;

    public JobRegistry(List<Job> discovered, AdvisoryLock lock, FrameworkMetrics metrics) {
        this.lock = lock;
        this.metrics = metrics;
        Map<String, Job> byName = new LinkedHashMap<>();
        discovered.forEach(job -> byName.put(job.name(), job));
        JOB_NAMES.forEach(name -> {
            Job job = byName.get(name);
            if (job != null) {
                jobs.put(name, job);
            }
        });
    }

    public List<String> names() {
        return List.copyOf(jobs.keySet());
    }

    public boolean has(String name) {
        return jobs.containsKey(name);
    }

    /** Run one job under its advisory lock. {@code -1} means another worker holds it. */
    public int run(String name) {
        Job job = jobs.get(name);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job '" + name + "'");
        }
        long started = System.nanoTime();
        try {
            int count = lock.runExclusively(name, job::runOnce);
            metrics.workerJob(name, "ok");
            if (count > 0) {
                log.info("job_ran job={} count={} ms={}", name, count, (System.nanoTime() - started) / 1_000_000);
            }
            return count;
        } catch (RuntimeException e) {
            metrics.workerJob(name, "error");
            throw e;
        }
    }
}
