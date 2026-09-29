package dev.synapse.featureflags;

import dev.synapse.core.context.RequestContextHolder;
import dev.synapse.core.context.TenantContext;
import dev.synapse.core.errors.ValidationFailedError;
import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.web.PlatformAdminOnly;
import dev.synapse.core.web.RequireTenant;
import dev.synapse.featureflags.dto.FlagCheck;
import dev.synapse.featureflags.dto.FlagCreate;
import dev.synapse.featureflags.dto.FlagRead;
import dev.synapse.featureflags.dto.FlagUpdate;
import dev.synapse.featureflags.dto.OverrideCreate;
import dev.synapse.featureflags.dto.OverrideRead;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
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
 * {@code /v1/feature-flags}.
 *
 * <p>Management (create/update/overrides) is platform-admin — tenants get 404,
 * never 403 (ADR 0008). Evaluation is a cheap org-scoped check any
 * authenticated member can call.
 */
@RestController
@RequestMapping("/v1/feature-flags")
public class FeatureFlagController {

    private final FeatureFlagService service;

    public FeatureFlagController(FeatureFlagService service) {
        this.service = service;
    }

    // ── Management (platform admin) ─────────────────────────────────────────────

    @GetMapping
    @PlatformAdminOnly
    public List<FlagRead> listFlags(
            HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<FeatureFlag> flags = service.listFlags();
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(flags.size()));
        return Pagination.sliceInMemory(flags, limit, offset).stream().map(FlagRead::from).toList();
    }

    @PostMapping
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.CREATED)
    public FlagRead createFlag(@Valid @RequestBody FlagCreate body) {
        return FlagRead.from(service.createFlag(body.key(), body.name(), body.description(), body.enabledOrDefault(),
            body.rolloutPercentage()));
    }

    @PatchMapping("/{key}")
    @PlatformAdminOnly
    public FlagRead updateFlag(@PathVariable String key, @Valid @RequestBody FlagUpdate body) {
        return FlagRead.from(service.updateFlag(key, body.enabled(), body.rolloutPercentage()));
    }

    @GetMapping("/{key}/overrides")
    @PlatformAdminOnly
    public List<OverrideRead> listOverrides(
            @PathVariable String key, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<FeatureFlagOverride> overrides = service.listOverrides(key);
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(overrides.size()));
        return Pagination.sliceInMemory(overrides, limit, offset).stream().map(OverrideRead::from).toList();
    }

    @PostMapping("/{key}/overrides")
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.CREATED)
    public OverrideRead setOverride(@PathVariable String key, @Valid @RequestBody OverrideCreate body) {
        if (!body.hasScope()) {
            // The reference's model validator: a scopeless override is a request error, not a 404.
            throw new ValidationFailedError("Invalid request: body", Map.of("errors", List.of(
                Map.of("loc", List.of("body"), "msg", "Value error, override requires organization_id or user_id",
                    "type", "value_error"))));
        }
        return OverrideRead.from(service.setOverride(key, body.organizationId(), body.userId(), body.enabled(), body.note()));
    }

    @DeleteMapping("/overrides/{overrideId}")
    @PlatformAdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(@PathVariable UUID overrideId) {
        service.deleteOverride(overrideId);
    }

    // ── Evaluation (org-scoped) ─────────────────────────────────────────────────

    @GetMapping("/check/{key}")
    @RequireTenant
    public FlagCheck check(@PathVariable String key, TenantContext tenant) {
        return new FlagCheck(key, service.isEnabled(key, tenant.organizationId(), RequestContextHolder.requireUser().userId()));
    }
}
