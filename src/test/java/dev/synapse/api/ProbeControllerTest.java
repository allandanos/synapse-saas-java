package dev.synapse.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.synapse.core.cache.CacheBackend;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.core.problem.ApiExceptionHandler;
import dev.synapse.core.problem.ProblemWriter;
import dev.synapse.core.security.SecurityConfig;
import dev.synapse.core.web.FeatureChecks;
import dev.synapse.core.web.FlagChecks;
import dev.synapse.core.web.PermissionChecks;
import dev.synapse.core.web.TenantAccess;
import dev.synapse.identity.ratelimit.RateLimiter;
import dev.synapse.tenancy.OrganizationController;
import dev.synapse.tenancy.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Slice test (no database): probes are public, protected routes are 401 problems, unknown paths 404 problems. */
@WebMvcTest({ProbeController.class, OrganizationController.class})
@Import({SecurityConfig.class, ProblemWriter.class, ApiExceptionHandler.class})
class ProbeControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean JdbcClient jdbc;
    @MockitoBean SynapseProperties props;
    @MockitoBean TenantAccess tenants;
    @MockitoBean PermissionChecks permissions;
    @MockitoBean FeatureChecks features;
    @MockitoBean FlagChecks flags;
    @MockitoBean OrganizationService organizations;
    // The milestone-7 seams the slice pulls in: the cache backend behind /readyz and the auth rate limiter's filter
    @MockitoBean CacheBackend cache;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean FrameworkMetrics metrics;

    @Test
    void healthzIsPublic() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void readyzIs503WhenTheDatabaseIsDown() throws Exception {
        when(jdbc.sql(anyString())).thenThrow(new RuntimeException("down"));
        mvc.perform(get("/readyz"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.checks.database").value("error: down"))
            // A dependency the deployment does not have is reported, never a failure on its own
            .andExpect(jsonPath("$.checks.redis").value("not_configured"));
    }

    @Test
    void metaDescribesTheDeployment() throws Exception {
        when(props.version()).thenReturn("0.1.0");
        when(props.billingProvider()).thenReturn("manual");
        when(props.identityProvider()).thenReturn("local");
        when(props.tenantIsolation()).thenReturn("app");
        mvc.perform(get("/v1/meta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.framework").value("synapse-saas"))
            .andExpect(jsonPath("$.tenant_isolation").value("app"));
    }

    @Test
    void protectedRoutesWithoutABearerAreA401ProblemThatEchoesTheRequestId() throws Exception {
        mvc.perform(get("/v1/orgs").header("X-Request-Id", "my-req-123"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("X-Request-Id", "my-req-123"))
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.type").value("https://synapse-saas.dev/problems/unauthorized"))
            .andExpect(jsonPath("$.title").value("unauthorized"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Missing bearer token"))
            .andExpect(jsonPath("$.instance").value("/v1/orgs"))
            .andExpect(jsonPath("$.request_id").value("my-req-123"));
    }

    @Test
    void unknownPathsAre404ProblemsEvenWithoutACredential() throws Exception {
        mvc.perform(get("/v1/nothing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("https://synapse-saas.dev/problems/not_found"))
            .andExpect(jsonPath("$.title").value("not found"))
            .andExpect(jsonPath("$.request_id").isNotEmpty());
    }
}
