package dev.synapse.subscriptions;

import dev.synapse.billing.BillingService;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.entitlements.EntitlementService;
import dev.synapse.entitlements.dto.EffectiveEntitlementsRead;
import dev.synapse.subscriptions.dto.CancelRequest;
import dev.synapse.subscriptions.dto.PlanChangeRequest;
import dev.synapse.subscriptions.dto.SubscriptionRead;
import dev.synapse.subscriptions.dto.TrialStartRequest;
import dev.synapse.usage.UsageService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/subscription}: the tenant's subscription, trial, plan change (through billing), cancel, resume. */
@RestController
@RequestMapping("/v1/subscription")
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final BillingService billing;
    private final EntitlementService entitlements;
    private final UsageService usage;

    public SubscriptionController(SubscriptionService subscriptions, BillingService billing, EntitlementService entitlements, UsageService usage) {
        this.subscriptions = subscriptions;
        this.billing = billing;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    @GetMapping
    @RequirePermission("billing:read")
    public Map<String, Object> current(TenantContext tenant) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subscription", subscriptions.currentForOrg(tenant.organizationId()).map(this::read).orElse(null));
        body.put("entitlements", EffectiveEntitlementsRead.from(entitlements.effectiveForOrg(tenant.organizationId())));
        body.put("usage", usage.summary(tenant.organizationId(), null));
        return body;
    }

    @PostMapping("/trial")
    @RequirePermission("billing:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionRead startTrial(@Valid @RequestBody TrialStartRequest body, TenantContext tenant) {
        return read(subscriptions.startTrial(tenant.organizationId(), body.planKey(), null));
    }

    /** Through the billing service: a hosted provider must be told, a local provider prorates (ADR 0004). */
    @PostMapping("/change")
    @RequirePermission("billing:manage")
    public SubscriptionRead changePlan(@Valid @RequestBody PlanChangeRequest body, TenantContext tenant) {
        return read(billing.changePlan(tenant.organizationId(), body.planKey()));
    }

    @PostMapping("/cancel")
    @RequirePermission("billing:manage")
    public SubscriptionRead cancel(@Valid @RequestBody CancelRequest body, TenantContext tenant) {
        return read(subscriptions.cancel(tenant.organizationId(), body.atPeriodEndOrDefault()));
    }

    @PostMapping("/resume")
    @RequirePermission("billing:manage")
    public SubscriptionRead resume(TenantContext tenant) {
        return read(subscriptions.resume(tenant.organizationId()));
    }

    private SubscriptionRead read(Subscription subscription) {
        return SubscriptionRead.from(subscription, subscriptions.planOf(subscription));
    }
}
