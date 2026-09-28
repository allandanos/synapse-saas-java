package dev.synapse.identity;

import dev.synapse.core.config.SynapseProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** The httpOnly refresh cookie the reference sets beside the JSON token pair ({@code synapse_rt}). */
@Component
public class RefreshCookie {

    public static final String NAME = "synapse_rt";

    private final SynapseProperties props;

    public RefreshCookie(SynapseProperties props) {
        this.props = props;
    }

    public void set(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(NAME, refreshToken)
            .httpOnly(true).sameSite("Lax").secure(props.cookieSecureEffective())
            .maxAge(Duration.ofSeconds(props.refreshTokenTtlSeconds())).path("/").build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
            .httpOnly(true).sameSite("Lax").secure(props.cookieSecureEffective()).maxAge(0).path("/").build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
