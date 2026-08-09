package io.rigger.api.controller;

import io.rigger.api.dto.GitOpsStateResponse;
import io.rigger.core.domain.security.RiggerContext;
import io.rigger.gitops.config.GitOpsProperties;
import io.rigger.security.rbac.RbacPolicyEngine;
import io.rigger.store.repository.GitOpsStateRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only view of the GitOps agent's sync state. GET /api/v1/gitops/state
 * The agent itself (rigger-gitops) writes this state; this controller never triggers a sync.
 */
@RestController
@RequestMapping("/api/v1/gitops")
public class GitOpsController {

    private final GitOpsStateRepository stateRepo;
    private final GitOpsProperties      props;
    private final RbacPolicyEngine      rbac;

    public GitOpsController(GitOpsStateRepository stateRepo, GitOpsProperties props, RbacPolicyEngine rbac) {
        this.stateRepo = stateRepo; this.props = props; this.rbac = rbac;
    }

    @GetMapping("/state")
    public ResponseEntity<GitOpsStateResponse> state(HttpServletRequest req) {
        var ctx = (RiggerContext) req.getAttribute("riggerContext");
        if (ctx == null) return ResponseEntity.status(401).build();
        rbac.authorize(ctx, "get", "GitOps");

        if (!props.isEnabled() || props.getRepository() == null) {
            return ResponseEntity.notFound().build();
        }

        return stateRepo.findById(props.getRepository())
            .map(s -> ResponseEntity.ok(new GitOpsStateResponse(
                true, s.getRepositoryUrl(), props.getBranch(), s.getLastAppliedCommit(),
                s.getLastAppliedAt(), s.getResult(), s.getErrorMessage())))
            .orElseGet(() -> ResponseEntity.ok(new GitOpsStateResponse(
                true, props.getRepository(), props.getBranch(), null, null, "PENDING", null)));
    }
}
