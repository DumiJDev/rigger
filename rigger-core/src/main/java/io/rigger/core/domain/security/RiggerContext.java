package io.rigger.core.domain.security;

import java.time.Instant;

/**
 * Security context extracted from the incoming request.
 * Populated by the authentication filter from the mTLS certificate or JWT.
 * Passed to every service method — never constructed by application code directly.
 *
 * @param identity  Authenticated identity.
 * @param namespace Namespace claimed by the request (from URL path).
 * @param sourceIp  Originating IP address (for audit logging).
 * @param timestamp When the request was received.
 */
public record RiggerContext(
        RiggerIdentity identity,
        String namespace,
        String sourceIp,
        Instant timestamp
) {
    public RiggerContext {
        if (identity == null) throw new IllegalArgumentException("Security context requires a non-null identity");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("Security context requires a non-blank namespace");
        if (sourceIp == null) sourceIp = "unknown";
        if (timestamp == null) timestamp = Instant.now();
    }

    /** Convenience: returns the identity name for logging (never a secret value). */
    public String identityName() {
        return identity.name();
    }
}
