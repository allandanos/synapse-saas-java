package dev.synapse.worker.jobs;

import dev.synapse.worker.Job;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-create {@code usage_events} partitions for the next
 * {@link #PARTITION_MONTHS_AHEAD} months, named {@code usage_events_yYYYYmMM}.
 * A lapsed run is survivable: rows for a month without a partition land in
 * {@code usage_events_default} instead of failing every insert.
 */
@Component
public class EnsurePartitionsJob implements Job {

    public static final String NAME = "ensure_partitions";
    public static final int PARTITION_MONTHS_AHEAD = 3;

    private final JdbcClient jdbc;

    public EnsurePartitionsJob(JdbcClient jdbc) {
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
                DO $$
                DECLARE
                    i INT;
                    p DATE;
                BEGIN
                    FOR i IN 0..%d LOOP
                        p := (date_trunc('month', now()) + make_interval(months => i))::date;
                        EXECUTE format(
                            'CREATE TABLE IF NOT EXISTS usage_events_y%%sm%%s PARTITION OF usage_events
                             FOR VALUES FROM (%%L) TO (%%L)',
                            to_char(p, 'YYYY'), to_char(p, 'MM'), p, p + INTERVAL '1 month'
                        );
                    END LOOP;
                END $$;
                """.formatted(PARTITION_MONTHS_AHEAD))
            .update();
        return PARTITION_MONTHS_AHEAD + 1;
    }
}
