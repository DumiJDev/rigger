package io.rigger.core.domain.security;

/** Outcome of an audited action. */
public enum AuditResult {
    /** Action completed successfully. */
    SUCCESS,
    /** Action was rejected by the RBAC policy engine. */
    DENIED,
    /** Action was attempted but failed due to an error. */
    ERROR
}
