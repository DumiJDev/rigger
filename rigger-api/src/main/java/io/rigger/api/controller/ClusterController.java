package io.rigger.api.controller;

import io.rigger.api.dto.*;
import io.rigger.core.domain.security.*;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.NodeRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** REST controller for cluster node status. Base path: /api/v1/cluster */
@RestController
@RequestMapping("/api/v1/cluster")
public class ClusterController {

    private final NodeRepository   nodeRepo;
    private final RbacPolicyEngine rbac;

    public ClusterController(NodeRepository nodeRepo, RbacPolicyEngine rbac) {
        this.nodeRepo = nodeRepo; this.rbac = rbac;
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
