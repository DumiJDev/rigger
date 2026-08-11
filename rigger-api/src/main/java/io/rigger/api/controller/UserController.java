package io.rigger.api.controller;

import io.rigger.api.dto.*;
import io.rigger.core.domain.security.*;
import io.rigger.security.auth.UserStore;
import io.rigger.security.rbac.RbacPolicyEngine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * User management endpoints. Requires CLUSTER_ADMIN role.
 *
 * POST   /api/v1/users              — create user
 * GET    /api/v1/users              — list users
 * DELETE /api/v1/users/{username}   — revoke user
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserStore        userStore;
    private final RbacPolicyEngine rbac;

    public UserController(UserStore userStore, RbacPolicyEngine rbac) {
        this.userStore = userStore;
        this.rbac      = rbac;
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> list(HttpServletRequest req) {
        authorize(req, "get");
        var users = userStore.listAll().stream()
            .map(id -> new UserResponse(id.name(), id.role().name(), id.namespace(), id.isActive()))
            .toList();
        return ResponseEntity.ok(users);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@RequestBody UserRequest body, HttpServletRequest req) {
        authorize(req, "create");
        if (userStore.exists(body.username())) {
            return ResponseEntity.status(409).build();
        }
        RiggerRole role;
        try {
            role = RiggerRole.valueOf(body.role().toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        var identity = userStore.createUser(body.username(), body.password(), role, body.namespace());
        return ResponseEntity.status(201)
            .body(new UserResponse(identity.name(), identity.role().name(),
                identity.namespace(), identity.isActive()));
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<Void> revoke(@PathVariable String username, HttpServletRequest req) {
        authorize(req, "delete");
        userStore.revoke(username);
        return ResponseEntity.noContent().build();
    }

    /**
     * Authorizes one user-management action.
     *
     * <p>The action must be one of the verbs {@code RbacPolicyEngine.ADMIN_ONLY} declares for
     * {@code "User"} ({@code get}/{@code create}/{@code delete}). A single {@code "manage"} verb
     * used to be passed here instead, so {@code /auth/permissions} described three capabilities
     * while the check tested a fourth that appears nowhere in the table — invisible only because
     * CLUSTER_ADMIN short-circuits before the table is read.
     */
    private void authorize(HttpServletRequest req, String action) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) throw new io.rigger.core.exception.AccessDeniedException("unknown", action, "User");
        // Cluster-scoped namespace: user management is not a namespaced operation, and the scope
        // gate in authorize() is what makes it admin-only.
        var adminCtx = new RiggerContext(ctx.identity(), "cluster", ctx.sourceIp(), ctx.timestamp());
        rbac.authorize(adminCtx, action, "User");
    }
}
