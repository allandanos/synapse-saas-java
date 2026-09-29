package dev.synapse.webhooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.billing.Signatures;
import dev.synapse.core.ids.Secrets;
import dev.synapse.core.metrics.FrameworkMetrics;
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
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, FernetCodec fernet,
                                  ObjectMapper json, FrameworkMetrics metrics) {
        this(deliveries, endpoints, fernet, json, metrics, HttpClient.newBuilder().connectTimeout(DELIVERY_TIMEOUT).build());
    }

    public WebhookDeliveryService(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, FernetCodec fernet,
                                  ObjectMapper json, FrameworkMetrics metrics, HttpClient http) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.fernet = fernet;
        this.json = json;
        this.metrics = metrics;
        this.http = http;
    }

    /** Create an endpoint; the plaintext secret is returned once and never stored. */
    @Transactional
    public Created createEndpoint(UUID organizationId, String url, List<String> events, String description) {
        String secret = "whsec_" + Secrets.urlsafeToken(24);
        UUID id = endpoints.insert(organizationId, url, fernet.encrypt(secret), description, events);
        return new Created(id, secret);
    }

    public record Created(UUID endpointId, String secret) {}

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
