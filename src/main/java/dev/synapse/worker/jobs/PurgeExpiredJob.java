package dev.synapse.worker.jobs;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.usage.UsageService;
import dev.synapse.worker.Job;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retention (reference: {@code worker/jobs.py:purge_expired}): delivered and
 * failed webhook deliveries after {@link #DELIVERY_RETENTION_DAYS}, exhausted
 * ones after {@link #EXHAUSTED_DELIVERY_RETENTION_DAYS} (they are the failure
 * audit trail, so they outlive routine rows), published outbox rows after
 * {@link #OUTBOX_RETENTION_DAYS}, spent usage idempotency keys after
 * {@link #IDEMPOTENCY_RETENTION_DAYS}, and audit logs past
 * {@code SYNAPSE_AUDIT_RETENTION_DAYS}. Presigned uploads that never completed
 * give their reserved bytes back.
 */
@Component
public class PurgeExpiredJob implements Job {

    public static final String NAME = "purge_expired";
    public static final int IDEMPOTENCY_RETENTION_DAYS = 90;
    public static final int DELIVERY_RETENTION_DAYS = 30;
    public static final int EXHAUSTED_DELIVERY_RETENTION_DAYS = 90;
    public static final int OUTBOX_RETENTION_DAYS = 7;
    public static final String STORAGE_METRIC = "storage_bytes";

    private record StaleUpload(UUID organizationId, long sizeBytes) {}

    private final JdbcClient jdbc;
    private final UsageService usage;
    private final SynapseProperties props;

    public PurgeExpiredJob(JdbcClient jdbc, UsageService usage, SynapseProperties props) {
        this.jdbc = jdbc;
        this.usage = usage;
        this.props = props;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    @Transactional
    public int runOnce() {
        jdbc.sql("DELETE FROM webhook_deliveries WHERE status <> 'exhausted' AND created_at < now() - make_interval(days => :days)")
            .param("days", DELIVERY_RETENTION_DAYS).update();
        jdbc.sql("DELETE FROM webhook_deliveries WHERE status = 'exhausted' AND created_at < now() - make_interval(days => :days)")
            .param("days", EXHAUSTED_DELIVERY_RETENTION_DAYS).update();
        jdbc.sql("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < now() - make_interval(days => :days)")
            .param("days", OUTBOX_RETENTION_DAYS).update();
        jdbc.sql("DELETE FROM audit_logs WHERE created_at < now() - make_interval(days => :days)")
            .param("days", props.auditRetentionDays()).update();

        List<StaleUpload> stale = jdbc.sql("""
                UPDATE stored_files SET deleted_at = now()
                WHERE status = 'pending' AND deleted_at IS NULL AND created_at < now() - make_interval(secs => :ttl)
                RETURNING organization_id, size_bytes
                """)
            .param("ttl", props.storagePresignSeconds() * 2)
            .query((rs, i) -> new StaleUpload(dev.synapse.core.db.Rows.uuid(rs, "organization_id"), rs.getLong("size_bytes")))
            .list();
        for (StaleUpload upload : stale) {
            usage.adjustGauge(upload.organizationId(), STORAGE_METRIC, -upload.sizeBytes(), false);
        }

        jdbc.sql("DELETE FROM usage_idempotency_keys WHERE created_at < now() - make_interval(days => :days)")
            .param("days", IDEMPOTENCY_RETENTION_DAYS).update();
        return 1;
    }
}
