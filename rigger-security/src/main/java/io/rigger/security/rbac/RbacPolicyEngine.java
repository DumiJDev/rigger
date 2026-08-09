package io.rigger.security.rbac;

import io.rigger.core.domain.security.*;
import io.rigger.core.exception.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Set;

/**
 * Evaluates RBAC policy for every authorised operation.
 * CLUSTER_ADMIN has unrestricted access.
 * All other roles follow the policy table.
 */
@Component
public class RbacPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(RbacPolicyEngine.class);

    private static final Set<Permission> POLICY = Set.of(
        // DEPLOYER — workload management
        Permission.of(RiggerRole.DEPLOYER, "apply",  "Deployment"),
        Permission.of(RiggerRole.DEPLOYER, "apply",  "Service"),
        Permission.of(RiggerRole.DEPLOYER, "apply",  "ConfigMap"),
        Permission.of(RiggerRole.DEPLOYER, "apply",  "Secret"),
        Permission.of(RiggerRole.DEPLOYER, "scale",  "Deployment"),
        Permission.of(RiggerRole.DEPLOYER, "delete", "Deployment"),
        Permission.of(RiggerRole.DEPLOYER, "delete", "Service"),
        Permission.of(RiggerRole.DEPLOYER, "delete", "ConfigMap"),
        Permission.of(RiggerRole.DEPLOYER, "delete", "Secret"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "Deployment"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "Service"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "ConfigMap"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "Secret"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "Pod"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "Node"),
        Permission.of(RiggerRole.DEPLOYER, "get",    "GitOps"),
        Permission.of(RiggerRole.DEPLOYER, "logs",   "Pod"),
        // VIEWER — read only
        Permission.of(RiggerRole.VIEWER, "get",  "Deployment"),
        Permission.of(RiggerRole.VIEWER, "get",  "Service"),
        Permission.of(RiggerRole.VIEWER, "get",  "ConfigMap"),
        Permission.of(RiggerRole.VIEWER, "get",  "Secret"),
        Permission.of(RiggerRole.VIEWER, "get",  "Pod"),
        Permission.of(RiggerRole.VIEWER, "get",  "Node"),
        Permission.of(RiggerRole.VIEWER, "get",  "GitOps"),
        Permission.of(RiggerRole.VIEWER, "logs", "Pod"),
        // GITOPS_AGENT — apply only
        Permission.of(RiggerRole.GITOPS_AGENT, "apply", "Deployment"),
        Permission.of(RiggerRole.GITOPS_AGENT, "apply", "Service"),
        Permission.of(RiggerRole.GITOPS_AGENT, "apply", "ConfigMap"),
        Permission.of(RiggerRole.GITOPS_AGENT, "apply", "Secret")
    );

    public void authorize(RiggerContext ctx, String action, String resource) {
        var identity = ctx.identity();

        // CLUSTER_ADMIN: unrestricted
        if (identity.role() == RiggerRole.CLUSTER_ADMIN) {
            log.debug("ALLOWED (cluster-admin): {} -> {}/{}", identity.name(), action, resource);
            return;
        }

        // Namespace scope: non-admin must be scoped to the request namespace
        if (!identity.isScopedTo(ctx.namespace())) {
            log.warn("DENIED namespace: {} tried {}/{} in ns:{} (assigned: {})",
                identity.name(), action, resource, ctx.namespace(), identity.namespace());
            throw new AccessDeniedException(identity.name(), action, ctx.namespace() + "/" + resource);
        }

        // Policy table check
        if (POLICY.contains(new Permission(identity.role(), action, resource))) {
            log.debug("ALLOWED: {} ({}) -> {}/{}", identity.name(), identity.role(), action, resource);
            return;
        }

        log.warn("DENIED: {} ({}) -> {}/{} in ns:{}", identity.name(), identity.role(), action, resource, ctx.namespace());
        throw new AccessDeniedException(identity.name(), action, ctx.namespace() + "/" + resource);
    }

    public boolean isAllowed(RiggerContext ctx, String action, String resource) {
        try { authorize(ctx, action, resource); return true; }
        catch (AccessDeniedException e) { return false; }
    }
}
