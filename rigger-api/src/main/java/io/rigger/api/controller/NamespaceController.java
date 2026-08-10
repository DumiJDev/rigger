package io.rigger.api.controller;

import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.domain.security.RiggerRole;
import io.rigger.store.repository.ResourceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Lists the namespaces that currently hold resources — backs the console's namespace picker,
 * which would otherwise be a free-text field.
 *
 * <p>Deliberately does <em>not</em> call {@code rbac.authorize(...)}, unlike every other resource
 * endpoint. The RBAC engine derives the request namespace from the URL path, and this path has no
 * namespace segment, so it resolves to {@code "cluster"} — which every namespace-scoped identity
 * fails {@code isScopedTo} against, meaning a VIEWER or DEPLOYER would get a 403 and lose the
 * namespace picker entirely. Authorization here is structural instead, and stricter: a
 * namespace-scoped caller is answered from its own token without the store ever being queried, so
 * it cannot learn that any other namespace exists. Only CLUSTER_ADMIN reaches the full listing.
 */
@RestController
@RequestMapping("/api/v1/namespaces")
public class NamespaceController {

    private final ResourceRepository store;

    public NamespaceController(ResourceRepository store) {
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<List<String>> list(HttpServletRequest req) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();

        var identity = ctx.identity();
        if (identity.role() != RiggerRole.CLUSTER_ADMIN) {
            return ResponseEntity.ok(
                identity.namespace() == null ? List.of() : List.of(identity.namespace()));
        }
        return ResponseEntity.ok(store.findDistinctNamespaces());
    }
}
