package dev.synapse.identity;

import dev.synapse.core.errors.AuthenticationError;
import dev.synapse.core.security.Principal;
import dev.synapse.identity.dto.AuthResponse;
import dev.synapse.identity.dto.ForgotPasswordRequest;
import dev.synapse.identity.dto.InviteAcceptRequest;
import dev.synapse.identity.dto.InviteAcceptResponse;
import dev.synapse.identity.dto.LoginRequest;
import dev.synapse.identity.dto.RefreshRequest;
import dev.synapse.identity.dto.RegisterRequest;
import dev.synapse.identity.dto.ResetPasswordRequest;
import dev.synapse.identity.dto.SwitchOrgRequest;
import dev.synapse.identity.dto.SwitchOrgResponse;
import dev.synapse.identity.dto.TokenPair;
import dev.synapse.identity.dto.UserWithOrgs;
import dev.synapse.tenancy.OrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/auth}: the local email/password identity provider (OIDC is milestone 7). */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final IdentityService identity;
    private final OrganizationService organizations;
    private final RefreshCookie cookie;

    public AuthController(IdentityService identity, OrganizationService organizations, RefreshCookie cookie) {
        this.identity = identity;
        this.organizations = organizations;
        this.cookie = cookie;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest body, HttpServletResponse response) {
        AuthResponse result = identity.register(body.email(), body.password(), body.displayName());
        cookie.set(response, result.tokens().refreshToken());
        return result;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        AuthResponse result = identity.login(body.email(), body.password(), request.getHeader("User-Agent"), request.getRemoteAddr());
        cookie.set(response, result.tokens().refreshToken());
        return result;
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody(required = false) RefreshRequest body, HttpServletRequest request, HttpServletResponse response) {
        String token = body != null && body.refreshToken() != null && !body.refreshToken().isEmpty()
            ? body.refreshToken() : RefreshCookie.read(request);
        if (token == null) {
            throw new AuthenticationError("Missing refresh token");
        }
        TokenPair pair = identity.refresh(token, request.getHeader("User-Agent"), request.getRemoteAddr());
        cookie.set(response, pair.refreshToken());
        return pair;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        identity.logout(RefreshCookie.read(request));
        cookie.clear(response);
    }

    @GetMapping("/me")
    public UserWithOrgs me(Principal principal) {
        return identity.me(principal.id());
    }

    @PostMapping("/switch-org")
    public SwitchOrgResponse switchOrg(@Valid @RequestBody SwitchOrgRequest body, Principal principal, HttpServletResponse response) {
        TokenPair pair = identity.switchOrg(principal.id(), body.organizationId());
        cookie.set(response, pair.refreshToken());
        return new SwitchOrgResponse(pair.accessToken(), pair.tokenType(), pair.expiresIn());
    }

    @PostMapping("/accept-invite")
    public InviteAcceptResponse acceptInvite(@Valid @RequestBody InviteAcceptRequest body, Principal principal) {
        return organizations.acceptInviteByToken(body.token(), principal);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        identity.requestPasswordReset(body.email());
        return Map.of("ok", true);
    }

    @PostMapping("/reset-password")
    public AuthResponse resetPassword(@Valid @RequestBody ResetPasswordRequest body, HttpServletResponse response) {
        AuthResponse result = identity.resetPassword(body.token(), body.password());
        cookie.set(response, result.tokens().refreshToken());
        return result;
    }
}
