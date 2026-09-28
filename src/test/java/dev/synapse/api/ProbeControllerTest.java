package dev.synapse.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.synapse.core.SecurityConfig;
import dev.synapse.core.SynapseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProbeController.class)
@Import(SecurityConfig.class)
class ProbeControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean JdbcClient jdbc;
    @MockitoBean SynapseProperties props;

    @Test
    void healthzIsPublic() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void readyzIs503WhenTheDatabaseIsDown() throws Exception {
        when(jdbc.sql(anyString())).thenThrow(new RuntimeException("down"));
        mvc.perform(get("/readyz"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.checks.database").value("error"));
    }

    @Test
    void metaDescribesTheDeployment() throws Exception {
        when(props.version()).thenReturn("0.1.0");
        when(props.billingProvider()).thenReturn("manual");
        when(props.identityProvider()).thenReturn("local");
        when(props.tenantIsolation()).thenReturn("app");
        mvc.perform(get("/v1/meta"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tenant_isolation").value("app"));
    }

    @Test
    void everythingElseIsDeniedUntilAuthLands() throws Exception {
        mvc.perform(get("/v1/orgs")).andExpect(status().isUnauthorized());
    }
}
