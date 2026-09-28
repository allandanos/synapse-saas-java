package dev.synapse.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Thin MockMvc wrapper mirroring the conformance suite's helpers (tenant = owner + org, bearer + X-Org-Id). */
public final class ApiClient {

    public static final String PASSWORD = "conformance-password-12345";

    public record Tenant(String accessToken, String refreshToken, String orgId, String userId, String email, String slug) {
        public Map<String, String> headers() {
            return Map.of("Authorization", "Bearer " + accessToken, "X-Org-Id", orgId);
        }

        public Map<String, String> bearer() {
            return Map.of("Authorization", "Bearer " + accessToken);
        }
    }

    public record Res(int status, JsonNode body, MockHttpServletResponse raw) {
        public String header(String name) {
            return raw.getHeader(name);
        }

        public String text(String field) {
            return body == null || body.get(field) == null || body.get(field).isNull() ? null : body.get(field).asText();
        }
    }

    private final MockMvc mvc;
    private final ObjectMapper json;

    public ApiClient(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public Res get(String path, Map<String, String> headers) throws Exception {
        return call(HttpMethod.GET, path, headers, null);
    }

    public Res delete(String path, Map<String, String> headers) throws Exception {
        return call(HttpMethod.DELETE, path, headers, null);
    }

    public Res post(String path, Map<String, String> headers, Object body) throws Exception {
        return call(HttpMethod.POST, path, headers, body);
    }

    public Res patch(String path, Map<String, String> headers, Object body) throws Exception {
        return call(HttpMethod.PATCH, path, headers, body);
    }

    public Res call(HttpMethod method, String path, Map<String, String> headers, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path);
        headers.forEach(builder::header);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        MockHttpServletResponse raw = mvc.perform(builder).andReturn().getResponse();
        String content = raw.getContentAsString();
        JsonNode parsed = content.isEmpty() ? null : json.readTree(content);
        return new Res(raw.getStatus(), parsed, raw);
    }

    public Res register(String email, String displayName) throws Exception {
        return post("/v1/auth/register", Map.of(), Map.of("email", email, "password", PASSWORD, "display_name", displayName));
    }

    /** A fresh owner + org, like the conformance suite's {@code make_tenant}. */
    public Tenant makeTenant(String label) throws Exception {
        String tag = uid();
        String email = label + "-" + tag + "@conformance.example.com";
        Res reg = register(email, label);
        if (reg.status() != 201) {
            throw new AssertionError("register failed: " + reg.status() + " " + reg.body());
        }
        String access = reg.body().at("/tokens/access_token").asText();
        Res org = post("/v1/orgs", Map.of("Authorization", "Bearer " + access), Map.of("name", "Org " + tag, "slug", "org-" + tag));
        if (org.status() != 201) {
            throw new AssertionError("create org failed: " + org.status() + " " + org.body());
        }
        return new Tenant(access, reg.body().at("/tokens/refresh_token").asText(), org.text("id"), reg.body().at("/user/id").asText(), email, "org-" + tag);
    }
}
