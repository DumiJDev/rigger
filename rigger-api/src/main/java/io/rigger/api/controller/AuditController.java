package io.rigger.api.controller;

import io.rigger.api.dto.*;
import io.rigger.core.domain.security.*;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.AuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Exposes audit log to CLUSTER_ADMIN. GET /api/v1/audit */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditRepository  auditRepo;
    private final RbacPolicyEngine rbac;

    public AuditController(AuditRepository auditRepo, RbacPolicyEngine rbac) {
        this.auditRepo = auditRepo; this.rbac = rbac;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false)    String namespace,
            HttpServletRequest req) {

        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();
        // "Audit" is the spelling RbacPolicyEngine.ADMIN_ONLY declares; the resource string is
        // matched literally, so "AuditLog" here made can('get','Audit') a claim nothing enforced.
        rbac.authorize(ctx, "get", "Audit");

        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by("timestamp").descending());
        var entries  = namespace != null
            ? auditRepo.findByNamespaceOrderByTimestampDesc(namespace, pageable)
            : auditRepo.findAll(pageable);

        return ResponseEntity.ok(entries.map(e -> new AuditResponse(
            e.getId(), e.getIdentityName(), e.getIdentityRole(), e.getAction(),
            e.getResourceKind(), e.getResourceName(), e.getNamespace(),
            e.getSourceIp(), e.getTimestamp(), e.getResult(), e.getErrorMessage()
        )));
    }
}
