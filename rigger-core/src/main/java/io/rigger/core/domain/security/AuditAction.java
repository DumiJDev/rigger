package io.rigger.core.domain.security;

/** All auditable actions in Rigger. Every action produces an AuditEntry. */
public enum AuditAction {
    // Authentication
    LOGIN_SUCCESS, LOGIN_FAILED, CERT_ISSUED, CERT_REVOKED,
    // Resource mutations
    APPLY, DELETE, SCALE,
    // Read operations (audited at DEBUG level, not persisted by default)
    GET, LIST, LOGS,
    // Cluster operations
    CLUSTER_UP, CLUSTER_SYNC, CLUSTER_DOWN,
    NODE_DRAIN, NODE_REMOVE,
    // Admin
    USER_APPROVE, USER_REVOKE, ROLE_ASSIGN,
    // GitOps
    GITOPS_APPLY, GITOPS_SYNC
}
