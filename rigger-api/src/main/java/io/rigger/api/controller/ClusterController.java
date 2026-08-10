package io.rigger.api.controller;

import io.rigger.api.dto.*;
import io.rigger.core.domain.security.*;
import io.rigger.provisioner.cluster.ClusterManifestParser;
import io.rigger.provisioner.cluster.ClusterOrchestrator;
import io.rigger.security.audit.AuditService;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.NodeRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** REST controller for cluster node status and lifecycle. Base path: /api/v1/cluster */
@RestController
@RequestMapping("/api/v1/cluster")
public class ClusterController {

    private final NodeRepository        nodeRepo;
    private final RbacPolicyEngine      rbac;
    private final AuditService          audit;
    private final ClusterManifestParser manifestParser;
    private final ClusterOrchestrator   orchestrator;

    public ClusterController(NodeRepository nodeRepo, RbacPolicyEngine rbac, AuditService audit,
                              ClusterManifestParser manifestParser, ClusterOrchestrator orchestrator) {
        this.nodeRepo = nodeRepo; this.rbac = rbac; this.audit = audit;
        this.manifestParser = manifestParser; this.orchestrator = orchestrator;
    }

    @PostMapping("/up")
    public ResponseEntity<Object> up(@RequestBody Map<String, Object> req, HttpServletRequest httpReq) throws Exception {
        var ctx = ctx(httpReq);
        rbac.authorize(ctx, "up", "Cluster");

        var spec = manifestParser.parseString((String) req.get("manifest"));
        var result = orchestrator.up(spec);

        audit.recordSuccess(ctx, AuditAction.CLUSTER_UP, "Cluster", spec.name(), null,
            result.successCount() + "/" + result.nodeResults().size() + " nodes active");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/sync")
    public ResponseEntity<Object> sync(@RequestBody Map<String, Object> req, HttpServletRequest httpReq) throws Exception {
        var ctx = ctx(httpReq);
        rbac.authorize(ctx, "sync", "Cluster");

        var spec = manifestParser.parseString((String) req.get("manifest"));
        orchestrator.sync(spec);

        audit.recordSuccess(ctx, AuditAction.CLUSTER_SYNC, "Cluster", spec.name(), null, null);
        return ResponseEntity.ok(Map.of("cluster", spec.name(), "status", "synced"));
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<NodeResponse>> nodes(HttpServletRequest req) {
        var ctx = ctx(req);
        rbac.authorize(ctx, "get", "Node");
        return ResponseEntity.ok(nodeRepo.findAll().stream()
            .map(n -> new NodeResponse(n.getName(), n.getIp(), n.getRole(), n.getStatus(),
                n.isPrimary(), n.getSwarmNodeId(), n.getLastSeenAt()))
            .toList());
    }

    @GetMapping
    public ResponseEntity<Object> status(HttpServletRequest req) {
        var ctx = ctx(req);
        rbac.authorize(ctx, "get", "Cluster");
        long active  = nodeRepo.findByStatus(io.rigger.core.domain.cluster.NodeStatus.ACTIVE).size();
        long total   = nodeRepo.count();
        return ResponseEntity.ok(java.util.Map.of(
            "activeNodes", active, "totalNodes", total, "status", active > 0 ? "healthy" : "degraded"));
    }

    private RiggerContext ctx(HttpServletRequest req) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) throw new io.rigger.core.exception.AccessDeniedException("unknown","any","cluster");
        return ctx;
    }
}
