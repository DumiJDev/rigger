package io.rigger.api.controller;

import io.rigger.api.dto.EventResponse;
import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.domain.security.RiggerRole;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.entity.EventEntity;
import io.rigger.store.repository.EventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only feed of operational events for the console.
 *
 * <p>A namespace-scoped identity is always confined to its own namespace regardless of what it
 * asks for — events name resources, so an unfiltered feed would leak activity from namespaces the
 * caller can't otherwise see.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private static final int MAX_PAGE_SIZE = 200;

    private final EventRepository  repo;
    private final RbacPolicyEngine rbac;

    public EventController(EventRepository repo, RbacPolicyEngine rbac) {
        this.repo = repo;
        this.rbac = rbac;
    }

    @GetMapping
    public ResponseEntity<Page<EventResponse>> list(
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest req) {

        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();

        var identity = ctx.identity();
        String effectiveNamespace = namespace;

        if (identity.role() != RiggerRole.CLUSTER_ADMIN) {
            // Ignore any requested namespace: scoped identities only ever see their own.
            effectiveNamespace = identity.namespace();
            if (effectiveNamespace == null) return ResponseEntity.ok(Page.empty());
            rbac.authorize(new RiggerContext(identity, effectiveNamespace, ctx.sourceIp(), ctx.timestamp()),
                "get", "Deployment");
        }

        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var results = effectiveNamespace == null || effectiveNamespace.isBlank()
            ? repo.findAllByOrderByOccurredAtDesc(pageable)
            : repo.findByNamespaceOrderByOccurredAtDesc(effectiveNamespace, pageable);

        return ResponseEntity.ok(results.map(this::toResponse));
    }

    private EventResponse toResponse(EventEntity e) {
        return new EventResponse(e.getId(), e.getType(), e.getResourceKind(), e.getResourceName(),
            e.getNamespace(), e.getActor(), e.getMessage(), e.getOccurredAt());
    }
}
