package io.rigger.api.controller;

import io.rigger.api.dto.LoginRequest;
import io.rigger.api.dto.LoginResponse;
import io.rigger.security.auth.JwtTokenService;
import io.rigger.security.auth.UserStore;
import io.rigger.security.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints. All paths under /api/v1/auth/** are PUBLIC
 * (excluded from the authentication filter in SecurityAutoConfiguration).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserStore       userStore;
    private final JwtTokenService jwtService;
    private final AuditService    auditService;

    public AuthController(UserStore userStore, JwtTokenService jwtService, AuditService auditService) {
        this.userStore    = userStore;
        this.jwtService   = jwtService;
        this.auditService = auditService;
    }

    /**
     * POST /api/v1/auth/login
     * Body: { "username": "admin", "password": "admin" }
     * Returns: { "token": "eyJ...", "username": "admin", "role": "CLUSTER_ADMIN", ... }
     *
     * Use the token as: Authorization: Bearer <token>
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req, HttpServletRequest httpReq) {
        var identity = userStore.authenticate(req.username(), req.password()).orElse(null);

        if (identity == null) {
            auditService.recordLogin(req.username(), null, httpReq.getRemoteAddr(), false,
                "Invalid credentials");
            return ResponseEntity.status(401).body(
                new io.rigger.api.dto.ErrorResponse(401, "Unauthorized",
                    "Invalid username or password", "/api/v1/auth/login"));
        }

        String token = jwtService.issue(identity);
        auditService.recordLogin(identity.name(), identity.role().name(),
            httpReq.getRemoteAddr(), true, null);

        return ResponseEntity.ok(new LoginResponse(
            token,
            identity.name(),
            identity.role().name(),
            identity.namespace(),
            jwtService.expirySeconds()
        ));
    }

    /**
     * GET /api/v1/auth/me
     * Returns current identity from the JWT token.
     * This endpoint IS protected (requires valid token).
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        var ctx = (io.rigger.core.domain.security.RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();
        var id = ctx.identity();
        return ResponseEntity.ok(new io.rigger.api.dto.UserResponse(
            id.name(), id.role().name(), id.namespace(), id.isActive()));
    }
}
