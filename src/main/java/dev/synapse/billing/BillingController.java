package dev.synapse.billing;

import dev.synapse.billing.BillingRefs.CheckoutResult;
import dev.synapse.billing.dto.CheckoutRequest;
import dev.synapse.billing.dto.CheckoutResponse;
import dev.synapse.billing.dto.PortalUrlResponse;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.security.Principal;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.subscriptions.Plan;
import dev.synapse.subscriptions.Subscription;
import dev.synapse.subscriptions.SubscriptionService;
import dev.synapse.tenancy.Organization;
import dev.synapse.tenancy.OrganizationService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/billing}: checkout, the offline-payment confirm, and the provider's billing portal. */
@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    /** The console's billing screen; checkout returns and the portal go back here. */
    public static final String DASHBOARD_PATH = "/dashboard/billing";

    private final BillingService billing;
    private final SubscriptionService subscriptions;
    private final OrganizationService organizations;
    private final BillingProviderRegistry providers;
    private final SynapseProperties props;

    public BillingController(BillingService billing, SubscriptionService subscriptions, OrganizationService organizations,
                             BillingProviderRegistry providers, SynapseProperties props) {
        this.billing = billing;
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.providers = providers;
        this.props = props;
    }

    @PostMapping("/checkout")
    @RequirePermission("billing:manage")
    public CheckoutResponse startCheckout(@Valid @RequestBody CheckoutRequest body, TenantContext tenant, Principal principal) {
        Organization organization = organizations.get(tenant.organizationId());
        Plan plan = subscriptions.planByKey(body.planKey());
        CheckoutResult result = billing.startCheckout(organization, plan, webUrl("?checkout=success"), webUrl("?checkout=cancelled"),
            principal.id());
        return new CheckoutResponse(result.url(), result.provider(), result.manualInstructions());
    }

    /**
     * Offline-payment flow: the tenant confirms, the operator collects out of band.
     * 409 on any provider that verifies payment itself (Stripe, Paddle, Xendit,
     * PayMongo) — those activate from the provider webhook only.
     */
    @PostMapping("/checkout/confirm")
    @RequirePermission("billing:manage")
    public Map<String, Object> confirmCheckout(@Valid @RequestBody CheckoutRequest body, TenantContext tenant, Principal principal) {
        Organization organization = organizations.get(tenant.organizationId());
        Plan plan = subscriptions.planByKey(body.planKey());
        Subscription subscription = billing.completeCheckout(organization, plan, null, principal.id(), BillingService.SOURCE_CLIENT_CONFIRM);
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("status", subscription.status());
        body2.put("plan_key", plan.key());
        body2.put("provider", providers.current().name());
        return body2;
    }

    @GetMapping("/portal-url")
    @RequirePermission("billing:manage")
    public PortalUrlResponse portalUrl(TenantContext tenant) {
        Organization organization = organizations.get(tenant.organizationId());
        return new PortalUrlResponse(billing.billingPortalUrl(organization, webUrl("")));
    }

    private String webUrl(String query) {
        String origin = props.webOrigin();
        return (origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin) + DASHBOARD_PATH + query;
    }
}
