package dev.synapse.authorization.fga;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.config.SynapseProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Thin OpenFGA HTTP client — check, write/delete tuples, list objects, stores,
 * models (reference: {@code authorization/fga.py}; no SDK).
 *
 * <p>Every method raises {@link FgaError} on transport or non-2xx; callers
 * decide the failure mode ({@code AuthorizationService} fails closed by default).
 */
@Component
public class FgaClient {

    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final SynapseProperties props;
    private final ObjectMapper json;
    private final HttpClient http;
    /** Overrides {@code SYNAPSE_OPENFGA_STORE_ID} once {@code write-model --create-store} minted one. */
    private String storeIdOverride;

    @org.springframework.beans.factory.annotation.Autowired
    public FgaClient(SynapseProperties props, ObjectMapper json) {
        this(props, json, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    public FgaClient(SynapseProperties props, ObjectMapper json, HttpClient http) {
        this.props = props;
        this.json = json;
        this.http = http;
    }

    public String url() {
        return props.openfgaUrl() == null ? "" : props.openfgaUrl().replaceAll("/+$", "");
    }

    public String storeId() {
        return storeIdOverride != null ? storeIdOverride : props.openfgaStoreId();
    }

    public void storeId(String storeId) {
        this.storeIdOverride = storeId;
    }

    public boolean configured() {
        return !url().isEmpty() && !storeId().isEmpty();
    }

    // ── Checks ───────────────────────────────────────────────────────────────────

    public boolean check(String user, String relation, String object) {
        Map<String, Object> body = withModel(new LinkedHashMap<>(
            Map.of("tuple_key", Map.of("user", user, "relation", relation, "object", object))));
        Object allowed = post(storePath("/check"), body).get("allowed");
        return Boolean.TRUE.equals(allowed);
    }

    @SuppressWarnings("unchecked")
    public List<String> listObjects(String user, String relation, String objectType) {
        Map<String, Object> body = withModel(new LinkedHashMap<>(
            Map.of("user", user, "relation", relation, "type", objectType)));
        Object objects = post(storePath("/list-objects"), body).get("objects");
        return objects instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    // ── Tuples ───────────────────────────────────────────────────────────────────

    /**
     * Write and delete tuples. Duplicate writes and missing deletes are
     * tolerated (one request per tuple keeps the operation idempotent under
     * retries — the outbox may replay it).
     */
    public void write(List<FgaTuple> writes, List<FgaTuple> deletes) {
        for (FgaTuple tuple : writes) {
            try {
                post(storePath("/write"), withModel(new LinkedHashMap<>(
                    Map.of("writes", Map.of("tuple_keys", List.of(tuple.asKey()))))));
            } catch (FgaError error) {
                if (!tolerable(error, "already exists", "already existed")) {
                    throw error;
                }
            }
        }
        for (FgaTuple tuple : deletes) {
            try {
                post(storePath("/write"), withModel(new LinkedHashMap<>(
                    Map.of("deletes", Map.of("tuple_keys", List.of(tuple.asKey()))))));
            } catch (FgaError error) {
                // OpenFGA 1.x answers "cannot delete a tuple which does not exist"; the
                // reference took the same two wordings in synapse-saas@b581b33.
                if (!tolerable(error, "not found", "does not exist", "did not exist")) {
                    throw error;
                }
            }
        }
    }

    private static boolean tolerable(FgaError error, String... phrases) {
        String body = error.body().toLowerCase(Locale.ROOT);
        for (String phrase : phrases) {
            if (body.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public List<FgaTuple> readTuples(String object) {
        Map<String, Object> data = post(storePath("/read"), new LinkedHashMap<>(
            Map.of("tuple_key", Map.of("object", object))));
        Object tuples = data.get("tuples");
        if (!(tuples instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(entry -> (Map<String, Object>) ((Map<String, Object>) entry).get("key"))
            .map(key -> new FgaTuple(String.valueOf(key.get("user")), String.valueOf(key.get("relation")),
                String.valueOf(key.get("object"))))
            .toList();
    }

    // ── Stores + models ──────────────────────────────────────────────────────────

    public String createStore(String name) {
        return String.valueOf(post("/stores", new LinkedHashMap<>(Map.of("name", name))).get("id"));
    }

    public String writeModel(Map<String, Object> model) {
        return String.valueOf(post(storePath("/authorization-models"), model).get("authorization_model_id"));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private Map<String, Object> withModel(Map<String, Object> body) {
        String modelId = props.openfgaModelId();
        if (modelId != null && !modelId.isEmpty()) {
            body.put("authorization_model_id", modelId);
        }
        return body;
    }

    private String storePath(String suffix) {
        if (storeId() == null || storeId().isEmpty()) {
            throw new FgaError("OpenFGA store id is not configured (SYNAPSE_OPENFGA_STORE_ID)");
        }
        return "/stores/" + storeId() + suffix;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        if (url().isEmpty()) {
            throw new FgaError("OpenFGA is not configured (SYNAPSE_OPENFGA_URL)");
        }
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new FgaError("OpenFGA request body is not serialisable: " + e.getMessage());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url() + path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        String token = props.openfgaApiToken();
        if (token != null && !token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new FgaError("OpenFGA unreachable: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FgaError("OpenFGA call was interrupted");
        }
        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() >= 300) {
            throw new FgaError("OpenFGA " + path + " answered " + response.statusCode(),
                Map.of("body", responseBody.substring(0, Math.min(500, responseBody.length()))));
        }
        if (responseBody.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(responseBody, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new FgaError("OpenFGA " + path + " returned a non-JSON response");
        }
    }
}
