package io.rigger.api.controller;

import io.rigger.api.dto.GitOpsConfigRequest;
import io.rigger.api.dto.GitOpsConfigResponse;
import io.rigger.api.dto.GitOpsStateResponse;
import io.rigger.core.domain.security.AuditAction;
import io.rigger.core.domain.security.RiggerContext;
import io.rigger.gitops.config.GitOpsConfigService;
import io.rigger.security.audit.AuditService;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.GitOpsStateRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * GitOps status and configuration.
 *
 * <p>{@code /state} is the read-only view of what the agent last did; {@code /config} lets a
 * cluster-admin reconfigure the agent at runtime (the agent re-reads it every poll), instead of
 * config being fixed at deploy time by environment variables.
 */
@RestController
@RequestMapping("/api/v1/gitops")
public class GitOpsController {

    private final GitOpsStateRepository stateRepo;
    private final GitOpsConfigService   configService;
    private final RbacPolicyEngine      rbac;
    private final AuditService          audit;

    public GitOpsController(GitOpsStateRepository stateRepo, GitOpsConfigService configService,
                            RbacPolicyEngine rbac, AuditService audit) {
        this.stateRepo     = stateRepo;
        this.configService = configService;
        this.rbac          = rbac;
        this.audit         = audit;
    }

    @GetMapping("/state")
    public ResponseEntity<GitOpsStateResponse> state(HttpServletRequest req) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();
        rbac.authorize(ctx, "get", "GitOps");

        var config = configService.current();
        if (!config.enabled() || config.repositoryUrl() == null) {
            return ResponseEntity.notFound().build();
        }

        return stateRepo.findById(config.repositoryUrl())
            .map(s -> ResponseEntity.ok(new GitOpsStateResponse(
                true, s.getRepositoryUrl(), config.branch(), s.getLastAppliedCommit(),
                s.getLastAppliedAt(), s.getResult(), s.getErrorMessage())))
            .orElseGet(() -> ResponseEntity.ok(new GitOpsStateResponse(
                true, config.repositoryUrl(), config.branch(), null, null, "PENDING", null)));
    }

    @GetMapping("/config")
    public ResponseEntity<GitOpsConfigResponse> getConfig(HttpServletRequest req) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();
        rbac.authorize(ctx, "get", "GitOps");
        return ResponseEntity.ok(toResponse(configService.current()));
    }

    /**
     * Replaces the stored configuration. Cluster-admin only — {@code "configure"} is absent from
     * the RBAC policy table for every other role, so they're rejected.
     */
    @PutMapping("/config")
    public ResponseEntity<GitOpsConfigResponse> putConfig(
            @RequestBody GitOpsConfigRequest body, HttpServletRequest req) {

        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();
        rbac.authorize(ctx, "configure", "GitOps");

        var settings = new GitOpsConfigService.GitOpsSettings(
            body.enabled(), body.repositoryUrl(), body.branch(), body.sshKeyPath(),
            body.authType(), body.httpsUsername(), body.httpsToken(),
            body.pollIntervalSeconds(),
            body.manifestPaths() == null ? List.of("manifests/") : body.manifestPaths(),
            body.namespaceMapping(), null, null);

        var saved = configService.save(settings, ctx.identityName());
        audit.recordSuccess(ctx, AuditAction.APPLY, "GitOps", "config", null,
            "enabled=" + saved.enabled() + " repository=" + saved.repositoryUrl()
                + " branch=" + saved.branch());

        return ResponseEntity.ok(toResponse(saved));
    }

    private GitOpsConfigResponse toResponse(GitOpsConfigService.GitOpsSettings s) {
        return new GitOpsConfigResponse(
            s.enabled(), s.repositoryUrl(), s.branch(), s.sshKeyPath(),
            s.authType(), s.httpsUsername(), s.httpsToken() != null && !s.httpsToken().isBlank(),
            s.pollIntervalSeconds(),
            s.manifestPaths(), s.namespaceMapping(),
            configService.isStored() ? "database" : "properties",
            s.updatedAt(), s.updatedBy());
    }
}
