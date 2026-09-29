package dev.synapse.core.security;

import dev.synapse.core.config.SynapseProperties;
import dev.synapse.core.problem.ProblemWriter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Stateless API: bearer tokens and API keys only. Probes, discovery, the
 * permission catalog and the credential-issuing auth routes are public; every
 * other route needs a principal (401 problem otherwise). Permission and tenant
 * checks happen per handler (see {@code dev.synapse.core.web}).
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, List<BearerAuthenticator> authenticators, ProblemWriter writer,
                            SynapseProperties props, ObjectProvider<RequestMappingHandlerMapping> mappings) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(c -> c.configurationSource(corsConfigurationSource(props)))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new ProblemAuthenticationEntryPoint(writer, mappings)))
            .addFilterAt(new BearerAuthenticationFilter(authenticators), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/readyz", "/metrics", "/v1/meta", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/permissions").permitAll()
                .requestMatchers(HttpMethod.POST,
                    "/v1/auth/register", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout",
                    "/v1/auth/forgot-password", "/v1/auth/reset-password").permitAll()
                // Provider webhooks authenticate themselves by signature/token, never by bearer
                .requestMatchers(HttpMethod.POST, "/v1/billing/webhooks/*").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .build();
    }

    /** Same policy as the reference's CORSMiddleware; built here (not a bean) because MVC's HandlerMappingIntrospector is also a CorsConfigurationSource. */
    static CorsConfigurationSource corsConfigurationSource(SynapseProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.corsOrigins());
        config.setAllowCredentials(true);
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Content-Disposition", "X-Total-Count"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
