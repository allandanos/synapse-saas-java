package dev.synapse.webhooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.Signatures;
import dev.synapse.core.audit.AuditService;
import dev.synapse.core.errors.WebhookDeliveryNotFoundError;
import dev.synapse.core.errors.WebhookEndpointNotFoundError;
import dev.synapse.core.ids.Secrets;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound webhook delivery (reference: {@code webhooks/service.py}).
 *
 * <p>Each attempt POSTs the stored payload wrapped in the delivery envelope,
 * signed {@code X-Synapse-Signature: t=<unix>,v1=<hex HMAC-SHA256 over
 * "<unix>.<body>">} with the endpoint's secret. 2xx marks it delivered;
 * anything else walks the backoff ladder and gives up at
 * {@link #MAX_DELIVERY_ATTEMPTS}, leaving the row visible and replayable.
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    public static final String SIGNATURE_HEADER = "X-Synapse-Signature";
    public static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);
    /** 1 m, 5 m, 30 m, 2 h, 6 h — then exhausted. */
    public static final List<Integer> DELIVERY_BACKOFF_SECONDS = List.of(60, 300, 1800, 7200, 21600);
    public static final int MAX_DELIVERY_ATTEMPTS = DELIVERY_BACKOFF_SECONDS.size() + 1;
    private static final int EXCERPT_LENGTH = 500;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final FernetCodec fernet;
    private final ObjectMapper json;
    private final FrameworkMetrics metrics;
    private final AuditService audit;
    private final OutboxWriter outbox;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, FernetCodec fernet,
                                  ObjectMapper json, FrameworkMetrics metrics, AuditService audit, OutboxWriter outbox) {
        this(deliveries, endpoints, fernet, json, metrics, audit, outbox,
            HttpClient.newBuilder().connectTimeout(DELIVERY_TIMEOUT).build());
    }

    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, FernetCodec fernet,
                                  ObjectMapper json, FrameworkMetrics metrics, AuditService audit, OutboxWriter outbox,
                                  HttpClient http) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.fernet = fernet;
        this.json = json;
        this.metrics = metrics;
        this.audit = audit;
        this.outbox = outbox;
        this.http = http;
    }

    /** Create an endpoint; the plaintext secret is returned once and never stored. */
    @Transactional
    public Created createEndpoint(UUID organizationId, String url, List<String> events, String description) {
        String secret = "whsec_" + Secrets.urlsafeToken(24);
        UUID id = endpoints.insert(organizationId, url, fernet.encrypt(secret), description, events);
        announce(Events.WEBHOOK_ENDPOINT_CREATED, organizationId, id, url, events);
        return new Created(id, secret);
    }

    /** The event and the audit row both carry the endpoint, never the secret. */
    private void announce(String eventType, UUID organizationId, UUID endpointId, String url, List<String> events) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("endpoint_id", endpointId.toString());
        payload.put("url", url);
        payload.put("events", List.copyOf(events));
        outbox.append(eventType, "webhook_endpoint", endpointId, organizationId, payload);
        audit.log(eventType, organizationId, null, "webhook_endpoint", endpointId, payload);
    }

    public record Created(UUID endpointId, String secret) {}

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> listEndpoints(UUID organizationId) {
        return endpoints.listForOrganization(organizationId);
    }

    /** Cross-tenant and unknown ids are the same 404 — existence is never leaked. */
    @Transactional(readOnly = true)
    public WebhookEndpoint getEndpoint(UUID endpointId, UUID organizationId) {
        return endpoints.findById(endpointId)
            .filter(e -> e.organizationId().equals(organizationId))
            .orElseThrow(() -> new WebhookEndpointNotFoundError("Webhook endpoint not found"));
    }

    @Transactional
    public void deleteEndpoint(UUID endpointId, UUID organizationId) {
        WebhookEndpoint endpoint = getEndpoint(endpointId, organizationId);
        endpoints.delete(endpoint.id()); // deliveries cascade with the endpoint
        announce(Events.WEBHOOK_ENDPOINT_DELETED, organizationId, endpoint.id(), endpoint.url(), endpoint.events());
    }

    @Transactional(readOnly = true)
    public Page listDeliveries(UUID organizationId, UUID endpointId, int limit, int offset) {
        return new Page(deliveries.page(organizationId, endpointId, limit, offset), deliveries.count(organizationId, endpointId));
    }

    public record Page(List<WebhookDelivery> rows, long total) {}

    /** Requeue a delivery: pending, attempts back to zero, due now. */
    @Transactional
    public WebhookDelivery retryDelivery(UUID deliveryId, UUID organizationId) {
        WebhookDelivery delivery = deliveries.findForOrg(deliveryId, organizationId)
            .orElseThrow(() -> new WebhookDeliveryNotFoundError("Delivery not found"));
        deliveries.resetForRetry(delivery.id(), Instant.now());
        return deliveries.findById(delivery.id()).orElseThrow();
    }

    /** Attempt one delivery. Returns success; state changes are persisted here. */
    @Transactional
    public boolean deliver(UUID deliveryId) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return false;
        }
        WebhookEndpoint endpoint = endpoints.findById(delivery.endpointId()).orElse(null);
        if (endpoint == null || !endpoint.active()) {
            deliveries.markFailure(delivery.id(), "failed", delivery.attempts(), null, "endpoint removed or inactive", null);
            return false;
        }

        byte[] body = serialise(buildEnvelope(delivery));
        long timestamp = Instant.now().getEpochSecond();
        String signature = Signatures.signPayload(body, fernet.decrypt(endpoint.secretEncrypted()), timestamp);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.url()))
            .timeout(DELIVERY_TIMEOUT)
            .header("Content-Type", "application/json")
            .header(SIGNATURE_HEADER, "t=" + timestamp + ",v1=" + signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            markFailure(delivery, null, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailure(delivery, null, "interrupted");
            return false;
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            deliveries.markDelivered(delivery.id(), Instant.now(), response.statusCode(), excerpt(response.body()));
            metrics.webhookDelivery("delivered");
            return true;
        }
        markFailure(delivery, response.statusCode(), excerpt(response.body()));
        metrics.webhookDelivery("failed");
        return false;
    }

    /** The envelope every endpoint receives. {@code data} is the outbox event's payload verbatim. */
    public static Map<String, Object> buildEnvelope(WebhookDelivery delivery) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", delivery.id().toString());
        envelope.put("event_type", delivery.eventType());
        envelope.put("organization_id", delivery.organizationId().toString());
        envelope.put("created_at", Instant.now().toString());
        envelope.put("data", delivery.payload());
        return envelope;
    }

    private void markFailure(WebhookDelivery delivery, Integer code, String error) {
        int attempts = delivery.attempts() + 1;
        int ceiling = Math.min(delivery.maxAttempts(), MAX_DELIVERY_ATTEMPTS);
        if (attempts >= ceiling) {
            deliveries.markFailure(delivery.id(), "exhausted", attempts, code, excerpt(error), null);
            metrics.webhookDelivery("exhausted");
            log.warn("webhook_delivery_exhausted delivery={} event_type={} attempts={}", delivery.id(), delivery.eventType(), attempts);
            return;
        }
        int backoff = DELIVERY_BACKOFF_SECONDS.get(Math.min(attempts - 1, DELIVERY_BACKOFF_SECONDS.size() - 1));
        deliveries.markFailure(delivery.id(), "pending", attempts, code, excerpt(error), Instant.now().plusSeconds(backoff));
    }

    private byte[] serialise(Map<String, Object> envelope) {
        try {
            return json.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook envelope is not serialisable", e);
        }
    }

    private static String excerpt(String body) {
        if (body == null) {
            return null;
        }
        return body.length() <= EXCERPT_LENGTH ? body : body.substring(0, EXCERPT_LENGTH);
    }
}
