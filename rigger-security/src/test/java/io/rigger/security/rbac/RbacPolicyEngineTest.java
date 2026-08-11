package io.rigger.security.rbac;

import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.domain.security.RiggerIdentity;
import io.rigger.core.domain.security.RiggerRole;
import io.rigger.core.exception.AccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the shape of the policy table itself, not just individual decisions.
 *
 * <p>The bug these tests exist for: DEPLOYER/VIEWER rows for cluster-scoped kinds (Node, GitOps)
 * that {@code authorize} could never reach, because the namespace gate rejects a scoped identity
 * against the literal {@code "cluster"} namespace those endpoints resolve to. The rows were
 * unreachable but {@code permissionsFor} read them, so the console offered pages the server 403'd.
 */
class RbacPolicyEngineTest {

    private static final String CLUSTER_NS = "cluster";

    private final RbacPolicyEngine rbac = new RbacPolicyEngine();

    @Test
    @DisplayName("no non-admin role may read cluster-scoped kinds")
    void clusterScopedKindsAreAdminOnly() {
        for (var role : new RiggerRole[]{RiggerRole.DEPLOYER, RiggerRole.VIEWER, RiggerRole.GITOPS_AGENT}) {
            var advertised = rbac.permissionsFor(role);
            for (var kind : new String[]{"Node", "GitOps", "Cluster", "User", "Audit"}) {
                assertFalse(advertised.containsKey(kind),
                    role + " must not be told it can read " + kind);
            }
            // And the check agrees with what is advertised: a scoped identity is denied both at
            // the cluster namespace (the gate) and, for completeness, in its own namespace.
            assertThrows(AccessDeniedException.class,
                () -> rbac.authorize(ctx(role, "team-a", CLUSTER_NS), "get", "Node"));
            assertThrows(AccessDeniedException.class,
                () -> rbac.authorize(ctx(role, "team-a", "team-a"), "get", "Node"));
            assertThrows(AccessDeniedException.class,
                () -> rbac.authorize(ctx(role, "team-a", CLUSTER_NS), "get", "GitOps"));
        }
    }

    @Test
    @DisplayName("DEPLOYER keeps full workload management in its own namespace")
    void deployerKeepsWorkloads() {
        var ctx = ctx(RiggerRole.DEPLOYER, "team-a", "team-a");
        for (var kind : new String[]{"Deployment", "Service", "ConfigMap", "Secret"}) {
            assertDoesNotThrow(() -> rbac.authorize(ctx, "get", kind), "get " + kind);
            assertDoesNotThrow(() -> rbac.authorize(ctx, "apply", kind), "apply " + kind);
            assertDoesNotThrow(() -> rbac.authorize(ctx, "delete", kind), "delete " + kind);
        }
        assertDoesNotThrow(() -> rbac.authorize(ctx, "scale", "Deployment"));
        assertDoesNotThrow(() -> rbac.authorize(ctx, "get", "Pod"));
        assertDoesNotThrow(() -> rbac.authorize(ctx, "logs", "Pod"));
    }

    @Test
    @DisplayName("VIEWER reads the four workload kinds but mutates nothing")
    void viewerIsReadOnly() {
        var ctx = ctx(RiggerRole.VIEWER, "team-a", "team-a");
        for (var kind : new String[]{"Deployment", "Service", "ConfigMap", "Secret"}) {
            assertDoesNotThrow(() -> rbac.authorize(ctx, "get", kind), "get " + kind);
            assertThrows(AccessDeniedException.class, () -> rbac.authorize(ctx, "apply", kind));
            assertThrows(AccessDeniedException.class, () -> rbac.authorize(ctx, "delete", kind));
        }
        assertThrows(AccessDeniedException.class, () -> rbac.authorize(ctx, "scale", "Deployment"));
    }

    @Test
    @DisplayName("a scoped identity cannot act on another namespace")
    void namespaceGateHolds() {
        var ctx = ctx(RiggerRole.DEPLOYER, "team-a", "team-b");
        assertThrows(AccessDeniedException.class, () -> rbac.authorize(ctx, "get", "Deployment"));
    }

    /**
     * The exact strings the admin-only controllers pass. A rename on either side silently makes
     * {@code permissionsFor} describe a capability no check tests — this pins them together.
     */
    @Test
    @DisplayName("admin permissions cover the resource strings the controllers actually use")
    void adminPermissionsMatchControllerStrings() {
        var admin = rbac.permissionsFor(RiggerRole.CLUSTER_ADMIN);
        assertTrue(admin.get("Audit").contains("get"), "AuditController authorizes get/Audit");
        assertTrue(admin.get("User").containsAll(java.util.Set.of("get", "create", "delete")),
            "UserController authorizes get|create|delete on User");
        assertTrue(admin.get("Node").contains("get"), "ClusterController authorizes get/Node");
        assertTrue(admin.get("GitOps").containsAll(java.util.Set.of("get", "configure")),
            "GitOpsController authorizes get and configure on GitOps");
        assertTrue(admin.get("Cluster").containsAll(java.util.Set.of("get", "up", "sync")));
        assertFalse(admin.containsKey("AuditLog"), "stale spelling must not reappear");
        // Actions stay per resource: a flat cross-product would tell the console a Secret can be
        // scaled and it would render the button.
        assertFalse(admin.get("Secret").contains("scale"));
        assertFalse(admin.get("Deployment").contains("configure"));
    }

    @Test
    @DisplayName("CLUSTER_ADMIN is unrestricted regardless of namespace")
    void adminBypassesEverything() {
        var ctx = ctx(RiggerRole.CLUSTER_ADMIN, null, CLUSTER_NS);
        assertDoesNotThrow(() -> rbac.authorize(ctx, "get", "Node"));
        assertDoesNotThrow(() -> rbac.authorize(ctx, "get", "Audit"));
        assertDoesNotThrow(() -> rbac.authorize(ctx, "delete", "User"));
    }

    private static RiggerContext ctx(RiggerRole role, String identityNamespace, String requestNamespace) {
        var identity = new RiggerIdentity(
            "id", role.name().toLowerCase() + "-user", role, identityNamespace,
            null, Instant.now(), null, java.util.Map.of());
        return new RiggerContext(identity, requestNamespace, "127.0.0.1", Instant.now());
    }
}
