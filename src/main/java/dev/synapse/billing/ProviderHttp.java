package dev.synapse.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.errors.BillingProviderError;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The providers' HTTP transport: one shared JDK client, form- or JSON-encoded
 * bodies, JSON responses. Injected so tests can point every provider at a local
 * stub server (reference: {@code core/http.py:get_http_client} + raw httpx).
 */
@Component
public class ProviderHttp {

    public static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderHttp(ObjectMapper json) {
        this(json, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public ProviderHttp(ObjectMapper json, HttpClient client) {
        this.json = json;
        this.client = client;
    }

    /** Form-encoded request (Stripe). {@code null} values are dropped, booleans become true/false. */
    public Map<String, Object> form(String method, String url, Map<String, ?> data, Map<String, String> headers, String label) {
        StringBuilder body = new StringBuilder();
        flatten("", data == null ? Map.of() : data).forEach((key, value) -> {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(encode(key)).append('=').append(encode(value));
        });
        return send(request(method, url, headers, "application/x-www-form-urlencoded", body.toString()), label);
    }

    /** JSON request (Paddle, Xendit, PayMongo). */
    public Map<String, Object> json(String method, String url, Object body, Map<String, String> headers, String label) {
        String payload;
        try {
            payload = body == null ? null : json.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BillingProviderError(label + " request body is not serialisable: " + e.getMessage());
        }
        return send(request(method, url, headers, "application/json", payload), label);
    }

    private HttpRequest request(String method, String url, Map<String, String> headers, String contentType, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private Map<String, Object> send(HttpRequest request, String label) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BillingProviderError(label + " API error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BillingProviderError(label + " API call was interrupted");
        }
        if (response.statusCode() >= 400) {
            throw new BillingProviderError(label + " API error: " + detail(response.body()));
        }
        return parse(response.body(), label);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body, String label) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(body, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BillingProviderError(label + " returned a non-JSON response");
        }
    }

    /** Stripe puts the human-readable reason in {@code error.message}; others echo the body. */
    @SuppressWarnings("unchecked")
    private String detail(String body) {
        try {
            Map<String, Object> parsed = json.readValue(body, Map.class);
            Object error = parsed.get("error");
            if (error instanceof Map<?, ?> map && map.get("message") != null) {
                return String.valueOf(map.get("message"));
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // fall through to the raw body
        }
        return body;
    }

    /** Stripe expects nested form params: {@code price_data[currency]=PHP}. */
    static Map<String, String> flatten(String prefix, Map<?, ?> data) {
        Map<String, String> flat = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            String full = prefix.isEmpty() ? String.valueOf(key) : prefix + "[" + key + "]";
            if (value instanceof Map<?, ?> nested) {
                flat.putAll(flatten(full, nested));
            } else if (value instanceof Boolean flag) {
                flat.put(full, flag ? "true" : "false");
            } else if (value != null) {
                flat.put(full, String.valueOf(value));
            }
        });
        return flat;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
