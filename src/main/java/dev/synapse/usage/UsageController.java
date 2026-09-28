package dev.synapse.usage;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.errors.ValidationFailedError;
import dev.synapse.core.web.RequireTenant;
import dev.synapse.entitlements.EntitlementResolver.EffectiveEntitlements;
import dev.synapse.entitlements.EntitlementService;
import dev.synapse.usage.dto.GaugeIn;
import dev.synapse.usage.dto.UsageBatchIn;
import dev.synapse.usage.dto.UsageCheckOut;
import dev.synapse.usage.dto.UsageEventIn;
import dev.synapse.usage.dto.UsageResultOut;
import dev.synapse.usage.dto.UsageSummaryOut;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/usage}: record (never blocks), consume (enforces), batches, gauges, check, summary. Membership only, no permission. */
@RestController
@RequestMapping("/v1/usage")
public class UsageController {

    public static final String BATCH_URL = "/v1/usage/consume-batch";

    private final UsageService usage;
    private final EntitlementService entitlements;

    public UsageController(UsageService usage, EntitlementService entitlements) {
        this.usage = usage;
        this.entitlements = entitlements;
    }

    /** Meter usage. Recording never blocks (soft path). */
    @PostMapping("/events")
    @RequireTenant
    @ResponseStatus(HttpStatus.CREATED)
    public List<UsageResultOut> recordEvents(@Valid @RequestBody UsageBatchIn body, TenantContext tenant) {
        return usage.recordMany(tenant.organizationId(), body.events());
    }

    /** Meter + enforce ONE event. 402 with upgrade hints on breach; more than one event is a 422. */
    @PostMapping("/consume")
    @RequireTenant
    public UsageResultOut consume(@Valid @RequestBody UsageBatchIn body, TenantContext tenant) {
        if (body.events().size() != 1) {
            throw new ValidationFailedError("consume takes exactly one event; use /usage/consume-batch for batches",
                Map.of("events", body.events().size(), "batch_url", BATCH_URL));
        }
        UsageEventIn event = body.events().get(0);
        return usage.consume(tenant.organizationId(), event.metric(), event.quantityOrDefault(), event.idempotencyKey(), event.properties());
    }

    /** Meter + enforce a batch atomically: the first breach 402s and NOTHING in the batch is counted. */
    @PostMapping("/consume-batch")
    @RequireTenant
    public List<UsageResultOut> consumeBatch(@Valid @RequestBody UsageBatchIn body, TenantContext tenant) {
        return usage.consumeMany(tenant.organizationId(), body.events());
    }

    /** Set ({@code value}) or move ({@code delta}) a gauge metric — seats, projects, bytes. */
    @PostMapping("/gauge")
    @RequireTenant
    public UsageResultOut gauge(@Valid @RequestBody GaugeIn body, TenantContext tenant) {
        if (!body.hasExactlyOne()) {
            throw new ValidationFailedError("Invalid request: body", Map.of("errors", List.of(
                Map.of("loc", List.of("body"), "msg", "Value error, provide exactly one of value or delta", "type", "value_error"))));
        }
        if (body.value() != null) {
            return usage.setGauge(tenant.organizationId(), body.metric(), body.value());
        }
        return usage.adjustGauge(tenant.organizationId(), body.metric(), body.delta(), true);
    }

    @GetMapping("/check")
    @RequireTenant
    public UsageCheckOut check(TenantContext tenant, @RequestParam String metric, @RequestParam(defaultValue = "1") @Min(1) long quantity) {
        return usage.check(tenant.organizationId(), metric, quantity);
    }

    /** One entitlement resolution for the whole summary (not one per metric). */
    @GetMapping("/summary")
    @RequireTenant
    public UsageSummaryOut summary(TenantContext tenant, @RequestParam(required = false) @Pattern(regexp = "^\\d{4}-\\d{2}$") String period) {
        LocalDate periodDate = parsePeriod(period);
        List<Map<String, Object>> rows = usage.summary(tenant.organizationId(), periodDate);
        EffectiveEntitlements effective = entitlements.effectiveForOrg(tenant.organizationId());
        List<UsageCheckOut> checks = rows.stream()
            .map(row -> UsageService.checkAgainst(effective, (String) row.get("metric"), ((Number) row.get("used")).longValue(), 1))
            .toList();
        return new UsageSummaryOut((periodDate != null ? periodDate : UsageService.monthBucket(Instant.now())).toString(), checks);
    }

    private static LocalDate parsePeriod(String period) {
        if (period == null) {
            return null;
        }
        try {
            return YearMonth.parse(period).atDay(1);
        } catch (DateTimeParseException e) {
            throw new ValidationFailedError("Invalid request: period", Map.of("errors", List.of(
                Map.of("loc", List.of("query", "period"), "msg", "Input should be a valid YYYY-MM period", "type", "value_error"))));
        }
    }
}
