package dev.synapse.core.security;

import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.core.errors.DomainError;
import dev.synapse.core.errors.NotFoundError;
import dev.synapse.core.problem.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 401 problem documents: the credential failure the filter recorded, else
 * "Missing bearer token". A path no controller serves is a 404 problem instead
 * (the reference routes first and authenticates per route, so unknown paths
 * never answer 401).
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemWriter writer;
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private volatile RequestMappingHandlerMapping mvcMapping;

    public ProblemAuthenticationEntryPoint(ProblemWriter writer, ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.writer = writer;
        this.mappings = mappings;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        if (!routeExists(request)) {
            writer.write(request, response, new NotFoundError("Not Found"));
            return;
        }
        Object recorded = request.getAttribute(BearerAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        DomainError error = recorded instanceof DomainError de ? de : new AuthenticationError("Missing bearer token");
        writer.write(request, response, error);
    }

    private boolean routeExists(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = controllerMapping();
        if (mapping == null) {
            return true;
        }
        try {
            return mapping.getHandler(request) != null;
        } catch (Exception methodOrMediaTypeMismatch) {
            return true; // the path exists; the route-level error (405/415) needs a principal first
        }
    }

    /** MVC's own mapping — Actuator contributes subclasses (controller endpoints) that must not be confused with it. */
    private RequestMappingHandlerMapping controllerMapping() {
        RequestMappingHandlerMapping cached = mvcMapping;
        if (cached == null) {
            cached = mappings.orderedStream()
                .filter(m -> m.getClass() == RequestMappingHandlerMapping.class)
                .findFirst()
                .orElse(null);
            mvcMapping = cached;
        }
        return cached;
    }
}
