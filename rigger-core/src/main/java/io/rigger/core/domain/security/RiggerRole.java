package io.rigger.core.domain.security;

/**
 * Built-in RBAC roles in Rigger.
 * All roles are namespace-scoped except CLUSTER_ADMIN.
 * Custom roles are stored in the database — this enum covers only built-ins.
 */
public enum RiggerRole {
    /** Full access including user management and role assignment. Global scope. */
    CLUSTER_ADMIN,
    /** Can apply, scale, get, logs, delete workloads within assigned namespaces. */
    DEPLOYER,
    /** Read-only: get and logs only. Cannot modify any resource. */
    VIEWER,
    /** Can apply manifests only. Cannot delete, cannot manage users. For CI/GitOps. */
    GITOPS_AGENT
}
