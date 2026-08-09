package io.rigger.security.auth;

import io.jsonwebtoken.JwtException;
import io.rigger.core.domain.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Authentication filter — runs on every request.
 *
 * Public paths (no token needed):
 *   POST /api/v1/auth/login
 *   GET  /actuator/health*
 *   GET  /ui/**
 *
 * All other paths require: Authorization: Bearer <token>
 */
@Component
public class RiggerAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RiggerAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private static final Set<String> PUBLIC_EXACT = Set.of(
        "/api/v1/auth/login",
        "/actuator/health",
        "/actuator/health/liveness",
        "/actuator/health/readiness"
    );

    private final JwtTokenService jwtService;
    private final UserStore       userStore;   // single source of truth

    public RiggerAuthenticationFilter(JwtTokenService jwtService, UserStore userStore) {
        this.jwtService = jwtService;
        this.userStore  = userStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // ── Public paths ────────────────────────────────────────────────
        if (isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        // ── Bearer JWT ──────────────────────────────────────────────────
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            try {
                var claims   = jwtService.validate(token);
                var ti       = jwtService.extractIdentity(claims);
                var identity = userStore.findByUsername(ti.name()).orElse(null);

                if (identity != null && identity.isActive()) {
                    String namespace = extractNamespace(request);
                    var ctx = new RiggerContext(identity, namespace,
                        request.getRemoteAddr(), Instant.now());
                    request.setAttribute("riggerContext", ctx);

                    var authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + identity.role().name()));
                    SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(identity, null, authorities));

                    log.debug("Authenticated: {} ({}) path={}", identity.name(), identity.role(), path);
                    chain.doFilter(request, response);
                    return;
                }

                log.warn("JWT valid but user not found or revoked: {}", ti.name());
            } catch (JwtException e) {
                log.debug("Invalid JWT: {}", e.getMessage());
            }
        }

        // ── Unauthenticated ─────────────────────────────────────────────
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            "{\"status\":401,\"title\":\"Unauthorized\"," +
            "\"detail\":\"Login required. POST /api/v1/auth/login {\\\"username\\\":\\\"admin\\\",\\\"password\\\":\\\"admin\\\"}\"}");
    }

    private boolean isPublic(String path) {
        if (PUBLIC_EXACT.contains(path)) return true;
        if (path.startsWith("/ui/") || path.equals("/ui")) return true;
        if (path.startsWith("/actuator/health")) return true;
        return false;
    }

    private String extractNamespace(HttpServletRequest request) {
        String[] parts = request.getRequestURI().split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("namespaces".equals(parts[i]) && i + 1 < parts.length)
                return parts[i + 1];
        }
        return "cluster";
    }
}
