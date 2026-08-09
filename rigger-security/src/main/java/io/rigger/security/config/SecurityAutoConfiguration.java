package io.rigger.security.config;

import io.rigger.security.auth.RiggerAuthenticationFilter;
import io.rigger.security.rbac.RbacPolicyEngine;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration.
 *
 * <p>Public (no token needed):
 * <ul>
 *   <li>POST /api/v1/auth/login</li>
 *   <li>GET  /ui/** (SPA static files)</li>
 *   <li>GET  /actuator/health*</li>
 * </ul>
 * Everything else requires a valid JWT.
 *
 * <p>RBAC itself is enforced by explicit {@code rbac.authorize(ctx, action, resource)} calls
 * at the top of each controller method (see {@link RbacPolicyEngine}) — not by an annotation.
 * An AOP-based {@code @RiggerAuthorize} mechanism was attempted and removed: it required
 * {@code RiggerContext} to be a method *parameter*, but controllers build it internally from
 * the request, and it needs a single resource kind fixed at compile time, but
 * {@code WorkloadController.apply()} handles a mixed batch of kinds per call. Any new
 * controller method that touches a resource MUST start with an explicit
 * {@code rbac.authorize(...)} call — there is no compiler-enforced safety net for this.
 */
@AutoConfiguration
@EnableWebSecurity
@EnableMethodSecurity
@ComponentScan(basePackages = "io.rigger.security")
public class SecurityAutoConfiguration {

    /** Used by {@link io.rigger.security.auth.UserStore} to hash and verify passwords. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RiggerAuthenticationFilter authFilter) throws Exception {

        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Login — must be public
                .requestMatchers("/api/v1/auth/login").permitAll()
                // Health probes — public for load balancers
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // SPA static assets — public
                .requestMatchers("/ui/**", "/ui", "/").permitAll()
                // Internal dispatches (error/async/forward/include — e.g. after a streaming
                // response like pod logs is already committed) reuse the ORIGINAL request URI,
                // not "/error" — so this must be matched by dispatcher type, not path — and
                // have no SecurityContext of their own; permit them rather than throwing a
                // secondary, response-already-sent AccessDeniedException. The actual endpoint
                // was already authorized on the initial REQUEST dispatch.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC,
                    DispatcherType.FORWARD, DispatcherType.INCLUDE).permitAll()
                // Everything else — checked by RiggerAuthenticationFilter
                .anyRequest().authenticated()
            )
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
