package dev.synapse.identity.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.synapse.core.config.Cidr;
import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.errors.RateLimitedError;
import dev.synapse.core.metrics.FrameworkMetrics;
import dev.synapse.core.problem.ProblemDocument;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Auth rate limiting (reference: {@code identity/rate_limit.py:AuthRateLimitMiddleware}).
 *
 * <p>Two counters protect every credential endpoint: the client IP (network
 * spray) and the target identity (stuffing one account). Either tripping
 * answers 429 with {@code Retry-After}. A backend failure <em>fails open</em>:
 * losing Redis costs the distributed counter, never availability.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after the request context, before the security chain
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Credential endpoints and the request field carrying the target identity (null ⇒ IP only). */
    public static final Map<String, String> AUTH_ROUTES = Map.of(
        "/v1/auth/login", "email",
        "/v1/auth/register", "email",
        "/v1/auth/forgot-password", "email",
        "/v1/auth/reset-password", "",   // token-based; IP limit only
        "/v1/auth/refresh", "",          // a stolen refresh token replayed at speed; IP limit only
        "/v1/auth/oidc/start", "",       // SSO round-trips; IP limit only
        "/v1/auth/oidc/callback", "");

    private final RateLimiter limiter;
    private final SynapseProperties props;
    private final FrameworkMetrics metrics;
    private final ObjectMapper json;
    private final List<Cidr> trustedProxies;

    public AuthRateLimitFilter(RateLimiter limiter, SynapseProperties props, FrameworkMetrics metrics, ObjectMapper json) {
        this.limiter = limiter;
        this.props = props;
        this.metrics = metrics;
        this.json = json;
        this.trustedProxies = ClientIp.parse(props.trustedProxies() == null ? List.of() : props.trustedProxies());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String identityField = AUTH_ROUTES.get(request.getRequestURI());
        if (identityField == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = ClientIp.of(request, trustedProxies);

        // The IP bucket applies to every auth route.
        try {
            limiter.check("auth:ip:" + ip, props.authRateLimitPerIp(), props.authRateWindowSeconds());
        } catch (RateLimitedError limited) {
            tooMany(request, response, ip, limited);
            return;
        } catch (RuntimeException e) {
            degraded(e);
        }

        HttpServletRequest downstream = request;
        // The identity bucket applies when the route carries a target account.
        if (!identityField.isEmpty() && "POST".equalsIgnoreCase(request.getMethod())) {
            BufferedBodyRequest buffered = new BufferedBodyRequest(request);
            downstream = buffered; // the handler still sees the bytes
            String identity = peek(buffered.body(), identityField);
            if (identity != null) {
                try {
                    limiter.check("auth:id:" + identity, props.authRateLimitPerIdentity(), props.authRateWindowSeconds());
                } catch (RateLimitedError limited) {
                    tooMany(request, response, ip, limited);
                    return;
                } catch (RuntimeException e) {
                    degraded(e);
                }
            }
        }
        chain.doFilter(downstream, response);
    }

    /** The identity field, lowercased; null when the body is absent or not the JSON we expect. */
    private String peek(byte[] body, String field) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Map<?, ?> parsed = json.readValue(body, Map.class);
            Object value = parsed.get(field);
            return value == null || String.valueOf(value).isEmpty() ? null : String.valueOf(value).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Redis (or the limiter) failed: log, count, and let the request through.
     * A Redis blip must not 429 every login.
     */
    private void degraded(RuntimeException error) {
        log.warn("auth_rate_limiter_degraded error={}", error.toString());
        safely(() -> metrics.authEvent("limiter_degraded"));
    }

    private void tooMany(HttpServletRequest request, HttpServletResponse response, String ip, RateLimitedError limited)
            throws IOException {
        long retryAfter = limited.retryAfterSeconds();
        log.warn("auth_rate_limited path={} ip={}", request.getRequestURI(), ip);
        safely(() -> metrics.authEvent("rate_limited")); // metrics must never fail the 429 itself
        Map<String, Object> doc = new LinkedHashMap<>(
            ProblemDocument.of(limited, request.getRequestURI(), null));
        doc.put("retry_after_seconds", retryAfter);
        response.setStatus(RateLimitedError.STATUS);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), doc);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // metrics are never load-bearing
        }
    }
}
