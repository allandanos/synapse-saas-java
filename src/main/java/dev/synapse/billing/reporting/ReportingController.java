package dev.synapse.billing.reporting;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.web.PlatformAdminOnly;
import dev.synapse.core.web.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spend (tenant) and revenue (operator) read models. Pure queries, safe to hit freely. */
@RestController
@RequestMapping("/v1/billing")
public class ReportingController {

    private final ReportingService reporting;

    public ReportingController(ReportingService reporting) {
        this.reporting = reporting;
    }

    /** This org's lifetime billing position: billed/paid/outstanding by status. */
    @GetMapping("/spend-summary")
    @RequirePermission("billing:read")
    public Map<String, Object> spendSummary(TenantContext tenant) {
        return reporting.orgSpendSummary(tenant.organizationId());
    }

    @GetMapping("/spend-monthly")
    @RequirePermission("billing:read")
    public List<Map<String, Object>> spendMonthly(TenantContext tenant) {
        return reporting.orgMonthlySpend(tenant.organizationId());
    }

    /** Platform-wide revenue view (MRR proxy, collected, outstanding). */
    @GetMapping("/admin/revenue-summary")
    @PlatformAdminOnly
    public Map<String, Object> revenueSummary() {
        return reporting.revenueSummary();
    }

    @GetMapping("/admin/revenue-monthly")
    @PlatformAdminOnly
    public List<Map<String, Object>> revenueMonthly() {
        return reporting.monthlyRevenue();
    }
}
