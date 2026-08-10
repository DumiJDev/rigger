package io.rigger.security.rbac;

import io.rigger.core.domain.security.*;
import io.rigger.core.exception.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Operations only CLUSTER_ADMIN can perform. These are enforced by <em>absence</em> from
     * {@link #POLICY} (every other role falls through to a denial), so they have to be listed
     * separately for {@link #permissionsFor} to describe an admin's capabilities accurately.
     * Keep in sync with the controllers that call {@code authorize} with these pairs.
     */
    private static final Map<String, Set<String>> ADMIN_ONLY = Map.of(
        "Cluster", Set.of("get", "up", "sync"),
        "GitOps",  Set.of("configure"),
        "User",    Set.of("get", "create", "delete"),
        "Audit",   Set.of("get")
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

    /**
     * The (action, resource) pairs a role may perform, as a resource → actions map.
     *
     * <p>Exposed so the console can drive its own affordances (hiding or disabling actions the
     * caller can't perform) from the same table this engine enforces, instead of a hand-copied
     * matrix in the frontend that silently drifts the first time this one changes. This is for
     * presentation only — the per-request {@link #authorize} check remains the sole enforcement.
     */
    public Map<String, Set<String>> permissionsFor(RiggerRole role) {
        if (role != RiggerRole.CLUSTER_ADMIN) {
            return POLICY.stream()
                .filter(p -> p.role() == role)
                .collect(Collectors.groupingBy(Permission::resource,
                    Collectors.mapping(Permission::action, Collectors.toSet())));
        }

        // CLUSTER_ADMIN bypasses the table entirely in authorize(), so there are no rows to read
        // back. Report the union of every action any role may perform on each resource, plus the
        // admin-only operations below. Crucially this is still *per resource* — reporting a flat
        // cross-product would tell the console that a Secret can be scaled or GitOps can stream
        // logs, and it would render buttons for operations that don't exist.
        var byResource = POLICY.stream()
            .collect(Collectors.groupingBy(Permission::resource,
                Collectors.mapping(Permission::action, Collectors.toCollection(java.util.HashSet::new))));

        ADMIN_ONLY.forEach((resource, actions) ->
            byResource.computeIfAbsent(resource, r -> new java.util.HashSet<>()).addAll(actions));

        return byResource.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
    }
}
