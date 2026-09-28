package dev.synapse.apikeys;

import dev.synapse.apikeys.dto.ApiKeyCreate;
import dev.synapse.apikeys.dto.ApiKeyCreated;
import dev.synapse.apikeys.dto.ApiKeyRead;
import dev.synapse.authorization.PermissionCatalog;
import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.context.UserContext;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.security.Principal;
import dev.synapse.core.web.RequirePermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Set;
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

/** {@code /v1/api-keys}: paged list (plain array + {@code X-Total-Count}), create-once secret, revoke. */
@RestController
@RequestMapping("/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePermission("apikey:manage")
    public List<ApiKeyRead> list(
            TenantContext tenant, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<ApiKey> keys = service.listKeys(tenant.organizationId());
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(keys.size()));
        return Pagination.sliceInMemory(keys, limit, offset).stream().map(ApiKeyRead::from).toList();
    }

    @PostMapping
    @RequirePermission("apikey:manage")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreated create(@Valid @RequestBody ApiKeyCreate body, TenantContext tenant, Principal principal) {
        // Bound by the CREATOR: the guard just bound the acting principal's effective permissions
        // (RBAC keys, "*" for platform admins, or the parent key's scopes when a key mints a key).
        UserContext actor = RequestContextHolder.requireUser();
        Set<String> creatorKeys = actor.permissionKeys().contains("*") ? PermissionCatalog.PERMISSION_KEYS : actor.permissionKeys();
        // A key minting a key: root the chain at the human who created the parent.
        UUID creatorId = actor.isApiKey() ? actor.apiKeyCreatorId() : principal.id();
        ApiKeyService.CreatedKey created = service.createKey(tenant.organizationId(), body.name(), body.scopesOrEmpty(),
            body.expiresInDays(), creatorId, creatorKeys);
        return ApiKeyCreated.from(created.key(), created.plaintext());
    }

    @DeleteMapping("/{keyId}")
    @RequirePermission("apikey:manage")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID keyId, TenantContext tenant) {
        service.revokeKey(keyId, tenant.organizationId());
    }
}
