package dev.synapse.agents;

import dev.synapse.agents.dto.AgentCreate;
import dev.synapse.agents.dto.AgentRead;
import dev.synapse.agents.dto.AgentUpdate;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.web.RequireFeature;
import dev.synapse.core.web.RequirePermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/agents} — the registry, not the runtime (ADR 0007).
 *
 * <p>The whole router sits behind the {@code agents} entitlement (403
 * {@code feature_not_entitled} with upgrade hints for plans without it) — one
 * declaration, not one call per handler — plus {@code agents:read} /
 * {@code agents:manage}.
 */
@RestController
@RequestMapping("/v1/agents")
@RequireFeature("agents")
public class AgentController {

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("agents:read")
    public List<AgentRead> list(
            TenantContext tenant, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<Agent> agents = service.listForOrg(tenant.organizationId());
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(agents.size()));
        return Pagination.sliceInMemory(agents, limit, offset).stream().map(AgentRead::from).toList();
    }

    @PostMapping
    @RequirePermission("agents:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRead create(@Valid @RequestBody AgentCreate body, TenantContext tenant) {
        return AgentRead.from(service.create(tenant.organizationId(), body.slug(), body.name(), body.description(), body.configOrEmpty()));
    }

    @GetMapping("/{agentId}")
    @RequirePermission("agents:read")
    public AgentRead get(@PathVariable UUID agentId, TenantContext tenant) {
        return AgentRead.from(service.get(agentId, tenant.organizationId()));
    }

    @PatchMapping("/{agentId}")
    @RequirePermission("agents:manage")
    public AgentRead update(@PathVariable UUID agentId, @Valid @RequestBody AgentUpdate body, TenantContext tenant) {
        return AgentRead.from(service.update(agentId, tenant.organizationId(), body.name(), body.description(), body.config()));
    }

    @PostMapping("/{agentId}/disable")
    @RequirePermission("agents:manage")
    public AgentRead disable(@PathVariable UUID agentId, TenantContext tenant) {
        return AgentRead.from(service.setStatus(agentId, tenant.organizationId(), Agent.DISABLED));
    }

    @PostMapping("/{agentId}/enable")
    @RequirePermission("agents:manage")
    public AgentRead enable(@PathVariable UUID agentId, TenantContext tenant) {
        return AgentRead.from(service.setStatus(agentId, tenant.organizationId(), Agent.ACTIVE));
    }

    @DeleteMapping("/{agentId}")
    @RequirePermission("agents:manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID agentId, TenantContext tenant) {
        service.delete(agentId, tenant.organizationId());
    }
}
