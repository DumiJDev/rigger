package io.rigger.security.config;

import io.rigger.security.auth.RiggerAuthenticationFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
 */
@AutoConfiguration
@EnableWebSecurity
@EnableMethodSecurity
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "io.rigger.security")
public class SecurityAutoConfiguration {

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
                // Everything else — checked by RiggerAuthenticationFilter
                .anyRequest().authenticated()
            )
            .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
