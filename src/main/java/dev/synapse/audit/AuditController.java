package dev.synapse.audit;

import dev.synapse.audit.dto.AuditEntryRead;
import dev.synapse.audit.dto.AuditPage;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.web.RequirePermission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/audit}: the org's own audit trail, newest first, filterable by event type and actor. */
@RestController
@RequestMapping("/v1/audit")
public class AuditController {

    private final AuditQueryRepository entries;

    public AuditController(AuditQueryRepository entries) {
        this.entries = entries;
    }

    @GetMapping
    @RequirePermission("audit:read")
    @Transactional(readOnly = true)
    public AuditPage list(
            TenantContext tenant,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "actor_user_id", required = false) UUID actorUserId,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return AuditPage.of(entries.page(tenant.organizationId(), eventType, actorUserId, limit, offset).stream()
            .map(AuditEntryRead::from).toList());
    }
}
