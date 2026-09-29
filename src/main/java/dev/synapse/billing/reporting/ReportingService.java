package dev.synapse.billing.reporting;

import dev.synapse.subscriptions.SubscriptionStateMachine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-model reporting over invoices and subscriptions
 * (reference: {@code billing/reporting.py}). Pure queries, no mutations.
 *
 * <p>Tenant-facing: per-org spend. Platform-facing: revenue (MRR proxy,
 * collected, outstanding, status mix).
 */
@Service
public class ReportingService {

    /** The reference reports a single platform currency here; `billed_cents` sums every status. */
    public static final String REPORT_CURRENCY = "PHP";
    private static final int MONTHS = 12;

    private final JdbcClient jdbc;

    public ReportingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Tenant: what has this org been billed? ────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> orgSpendSummary(UUID organizationId) {
        Map<String, Long> byStatus = statusTotals(organizationId);
        long billed = byStatus.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organization_id", organizationId.toString());
        out.put("billed_cents", billed);
        out.put("paid_cents", byStatus.getOrDefault("paid", 0L));
        out.put("outstanding_cents", byStatus.getOrDefault("open", 0L));
        out.put("void_cents", byStatus.getOrDefault("void", 0L));
        out.put("by_status", byStatus);
        out.put("currency", REPORT_CURRENCY);
        return out;
    }

    /** Billed totals per month (the {@code issued_at} bucket), oldest → newest. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> orgMonthlySpend(UUID organizationId) {
        return jdbc.sql("""
                SELECT to_char(date_trunc('month', issued_at), 'YYYY-MM') AS month,
                       COALESCE(SUM(total_cents), 0) AS total_cents,
                       count(*) AS invoice_count
                FROM invoices
                WHERE organization_id = :org AND status IN ('paid', 'open') AND issued_at IS NOT NULL
                GROUP BY month
                ORDER BY min(issued_at)
                LIMIT :months
                """)
            .param("org", organizationId).param("months", MONTHS)
            .query((rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("month", rs.getString("month"));
                row.put("total_cents", rs.getLong("total_cents"));
                row.put("invoices", rs.getLong("invoice_count"));
                return row;
            })
            .list();
    }

    private Map<String, Long> statusTotals(UUID organizationId) {
        Map<String, Long> totals = new LinkedHashMap<>();
        jdbc.sql("SELECT status, COALESCE(SUM(total_cents), 0) AS total FROM invoices WHERE organization_id = :org GROUP BY status")
            .param("org", organizationId)
            .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("total")))
            .list()
            .forEach(entry -> totals.put(entry.getKey(), entry.getValue()));
        return totals;
    }

    // ── Platform: revenue view ────────────────────────────────────────────────────

    /**
     * MRR proxy: the sum of the occupying subscriptions' snapshot prices. Honest
     * label — it ignores proration and coupons — but the right first number.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> revenueSummary() {
        String[] occupying = SubscriptionStateMachine.OCCUPYING_STATUSES.toArray(String[]::new);
        long mrr = jdbc.sql("""
                SELECT COALESCE(SUM(CAST(COALESCE(plan_snapshot ->> 'price_cents', '0') AS bigint)), 0)
                FROM subscriptions WHERE status = ANY(:statuses)
                """)
            .param("statuses", occupying).query(Long.class).single();
        long collected = jdbc.sql("SELECT COALESCE(SUM(total_cents), 0) FROM invoices WHERE status = 'paid'").query(Long.class).single();
        long outstanding = jdbc.sql("SELECT COALESCE(SUM(total_cents), 0) FROM invoices WHERE status = 'open'").query(Long.class).single();
        long payingOrgs = jdbc.sql("""
                SELECT count(DISTINCT organization_id) FROM subscriptions
                WHERE status = ANY(:statuses) AND CAST(COALESCE(plan_snapshot ->> 'price_cents', '0') AS bigint) > 0
                """)
            .param("statuses", occupying).query(Long.class).single();
        Map<String, Object> mix = new LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS count FROM invoices GROUP BY status")
            .query((rs, i) -> Map.entry(rs.getString("status"), rs.getLong("count")))
            .list()
            .forEach(entry -> mix.put(entry.getKey(), entry.getValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mrr_proxy_cents", mrr);
        out.put("collected_cents", collected);
        out.put("outstanding_cents", outstanding);
        out.put("paying_organizations", payingOrgs);
        out.put("invoices_by_status", mix);
        out.put("as_of", Instant.now().toString());
        return out;
    }

    /** Collected (paid) revenue per month across every org. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> monthlyRevenue() {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.sql("""
                SELECT to_char(date_trunc('month', paid_at), 'YYYY-MM') AS month,
                       COALESCE(SUM(total_cents), 0) AS collected_cents,
                       count(*) AS invoice_count
                FROM invoices
                WHERE status = 'paid' AND paid_at IS NOT NULL
                GROUP BY month
                ORDER BY min(paid_at)
                LIMIT :months
                """)
            .param("months", MONTHS)
            .query((rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("month", rs.getString("month"));
                row.put("collected_cents", rs.getLong("collected_cents"));
                row.put("invoices", rs.getLong("invoice_count"));
                return row;
            })
            .list());
        return rows;
    }
}
