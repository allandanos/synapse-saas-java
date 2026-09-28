package dev.synapse.core.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter: honours an inbound {@code X-Request-Id} (or mints
 * {@code req_<16 hex>}), echoes it, opens the request context, and adds the
 * reference's security headers. Runs before Spring Security so problem
 * documents written by the auth entry point carry the request id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestScopeFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        RequestContextHolder.open(requestId);
        MDC.put("request_id", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContextHolder.clear();
            MDC.clear();
        }
    }
}
