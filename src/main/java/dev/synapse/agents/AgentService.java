package dev.synapse.agents;

import dev.synapse.core.audit.AuditService;
import dev.synapse.core.errors.ConflictError;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.outbox.Events;
import dev.synapse.core.outbox.OutboxWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent registry service (reference: {@code agents/service.py}).
 *
 * <p>CRUD over org-scoped agents with lifecycle events through the outbox.
 * Every mutation emits its event INSIDE the transaction (webhooks fan out via
 * the worker); every read is tenant-filtered by construction. Deletes are soft
 * — registry rows are billing history — and a deleted slug stays taken.
 */
@Service
public class AgentService {

    private final AgentRepository agents;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public AgentService(AgentRepository agents, AuditService audit, OutboxWriter outbox) {
        this.agents = agents;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<Agent> listForOrg(UUID organizationId) {
        return agents.listForOrganization(organizationId);
    }

    @Transactional(readOnly = true)
    public Agent get(UUID agentId, UUID organizationId) {
        return scoped(agentId, organizationId);
    }

    @Transactional
    public Agent create(UUID organizationId, String slug, String name, String description, Map<String, Object> config) {
        // Soft-deleted rows still hold their slug: reuse is a conflict, not a resurrection.
        if (agents.findBySlug(organizationId, slug).isPresent()) {
            throw new ConflictError("An agent with slug '" + slug + "' already exists in this organization", Map.of("slug", slug));
        }
        Agent agent = agents.insert(organizationId, slug, name, description, config == null ? Map.of() : config);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", agent.slug());
        payload.put("name", agent.name());
        outbox.append(Events.AGENT_REGISTERED, "agent", agent.id(), organizationId, payload);
        audit.log("agent.registered", organizationId, null, "agent", agent.id(), payload);
        return agent;
    }

    @Transactional
    public Agent update(UUID agentId, UUID organizationId, String name, String description, Map<String, Object> config) {
        Agent agent = scoped(agentId, organizationId);
        Map<String, Object> diff = new LinkedHashMap<>();
        if (name != null && !name.equals(agent.name())) {
            diff.put("name", change(agent.name(), name));
        }
        if (description != null && !description.equals(agent.description())) {
            diff.put("description", change(agent.description(), description));
        }
        if (config != null && !config.equals(agent.config())) {
            diff.put("config", "updated");
        }
        if (diff.isEmpty()) {
            return agent;
        }
        Agent updated = agents.update(agentId, organizationId,
            name != null ? name : agent.name(),
            description != null ? description : agent.description(),
            config != null ? config : agent.config());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", updated.slug());
        payload.put("changed", List.copyOf(new TreeSet<>(diff.keySet())));
        outbox.append(Events.AGENT_UPDATED, "agent", updated.id(), organizationId, payload);
        audit.log("agent.updated", organizationId, null, "agent", updated.id(), diff);
        return updated;
    }

    @Transactional
    public Agent setStatus(UUID agentId, UUID organizationId, String status) {
        if (!Agent.ACTIVE.equals(status) && !Agent.DISABLED.equals(status)) {
            throw new NotFoundError("Unknown status");
        }
        Agent agent = scoped(agentId, organizationId);
        if (status.equals(agent.status())) {
            return agent;
        }
        Agent updated = agents.setStatus(agentId, organizationId, status);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", updated.slug());
        payload.put("status", status);
        outbox.append(Agent.DISABLED.equals(status) ? Events.AGENT_DISABLED : Events.AGENT_UPDATED,
            "agent", updated.id(), organizationId, payload);
        audit.log("agent." + status, organizationId, null, "agent", updated.id(), Map.of("status", status));
        return updated;
    }

    @Transactional
    public void delete(UUID agentId, UUID organizationId) {
        Agent agent = scoped(agentId, organizationId);
        agents.softDelete(agentId, organizationId, Instant.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slug", agent.slug());
        payload.put("deleted", true);
        outbox.append(Events.AGENT_DISABLED, "agent", agent.id(), organizationId, payload);
        audit.log("agent.deleted", organizationId, null, "agent", agent.id(), Map.of());
    }

    private Agent scoped(UUID agentId, UUID organizationId) {
        return agents.findLive(agentId, organizationId)
            .orElseThrow(() -> new NotFoundError("Agent not found", Map.of("agent_id", agentId.toString())));
    }

    private static Map<String, Object> change(Object from, Object to) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("from", from);
        value.put("to", to);
        return value;
    }
}
