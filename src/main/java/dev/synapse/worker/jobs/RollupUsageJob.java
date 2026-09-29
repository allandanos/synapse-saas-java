package dev.synapse.worker.jobs;

import dev.synapse.worker.Job;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Hourly drift correction: rebuild the current period's counters from the raw events. */
@Component
public class RollupUsageJob implements Job {

    public static final String NAME = "rollup_usage";

    private final JdbcClient jdbc;

    public RollupUsageJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    @Transactional
    public int runOnce() {
        jdbc.sql("""
                INSERT INTO usage_counters (organization_id, metric, period_start, quantity_total, last_event_at)
                SELECT organization_id, metric, date_trunc('month', occurred_at)::date, SUM(quantity), MAX(occurred_at)
                FROM usage_events
                WHERE occurred_at >= date_trunc('month', now())
                GROUP BY organization_id, metric, date_trunc('month', occurred_at)::date
                ON CONFLICT (organization_id, metric, period_start)
                DO UPDATE SET quantity_total = EXCLUDED.quantity_total, last_event_at = EXCLUDED.last_event_at
                """)
            .update();
        return 1;
    }
}
