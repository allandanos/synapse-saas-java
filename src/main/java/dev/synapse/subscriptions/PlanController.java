package dev.synapse.subscriptions;

import dev.synapse.core.pagination.Pagination;
import dev.synapse.core.security.Principal;
import dev.synapse.subscriptions.dto.PlanRead;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/plans}: the public catalog, plain array + {@code X-Total-Count}; any authenticated principal, no tenant needed. */
@RestController
public class PlanController {

    private final PlanRepository plans;

    public PlanController(PlanRepository plans) {
        this.plans = plans;
    }

    @GetMapping("/v1/plans")
    public List<PlanRead> list(
            Principal principal, HttpServletResponse response,
            @RequestParam(defaultValue = "" + Pagination.DEFAULT_PAGE_LIMIT) @Min(1) @Max(Pagination.MAX_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        response.setHeader(Pagination.TOTAL_COUNT_HEADER, String.valueOf(plans.countPublic()));
        return plans.listPublic(limit, offset).stream().map(PlanRead::from).toList();
    }
}
