package dev.synapse.webhooks;

import dev.synapse.core.context.TenantContext;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.web.RequirePermission;
import dev.synapse.webhooks.dto.WebhookDeliveryRead;
import dev.synapse.webhooks.dto.WebhookEndpointCreate;
import dev.synapse.webhooks.dto.WebhookEndpointCreated;
import dev.synapse.webhooks.dto.WebhookEndpointRead;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/webhooks}: endpoint management and the delivery log, over the
 * delivery engine the worker drives. Every route needs {@code webhook:manage};
 * rows are org-scoped, so a foreign id is a 404 like any other.
 *
 * <p>The endpoint secret is minted and Fernet-encrypted at creation and
 * returned exactly once — no read path ever exposes it again.
 */
@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    private final WebhookDeliveryService service;

    public WebhookController(WebhookDeliveryService service) {
        this.service = service;
    }

    @GetMapping("/endpoints")
    @RequirePermission("webhook:manage")
    public List<WebhookEndpointRead> listEndpoints(
            TenantContext tenant, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<WebhookEndpoint> endpoints = service.listEndpoints(tenant.organizationId());
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(endpoints.size()));
        return Pagination.sliceInMemory(endpoints, limit, offset).stream().map(WebhookEndpointRead::from).toList();
    }

    @PostMapping("/endpoints")
    @RequirePermission("webhook:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointCreated createEndpoint(@Valid @RequestBody WebhookEndpointCreate body, TenantContext tenant) {
        String url = WebhookUrls.requireHttpUrl(body.url());
        WebhookDeliveryService.Created created =
            service.createEndpoint(tenant.organizationId(), url, body.eventsOrEmpty(), body.description());
        return WebhookEndpointCreated.from(service.getEndpoint(created.endpointId(), tenant.organizationId()), created.secret());
    }

    @DeleteMapping("/endpoints/{endpointId}")
    @RequirePermission("webhook:manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEndpoint(@PathVariable UUID endpointId, TenantContext tenant) {
        service.deleteEndpoint(endpointId, tenant.organizationId());
    }

    @GetMapping("/deliveries")
    @RequirePermission("webhook:manage")
    public List<WebhookDeliveryRead> listDeliveries(
            TenantContext tenant, HttpServletResponse response,
            @RequestParam(name = "endpoint_id", required = false) UUID endpointId,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        WebhookDeliveryService.Page page = service.listDeliveries(tenant.organizationId(), endpointId, limit, offset);
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(page.total()));
        return page.rows().stream().map(WebhookDeliveryRead::from).toList();
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    @RequirePermission("webhook:manage")
    public WebhookDeliveryRead retryDelivery(@PathVariable UUID deliveryId, TenantContext tenant) {
        return WebhookDeliveryRead.from(service.retryDelivery(deliveryId, tenant.organizationId()));
    }
}
