package io.rigger.core.domain.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * Represents an authenticated identity in Rigger.
 * Created when an admin approves a CLI certificate signing request (CSR)
 * or when a user logs in via OIDC.
 *
 * @param id          Unique identity ID (UUID).
 * @param name        Human-readable name (CN from certificate or OIDC sub).
 * @param role        Assigned built-in role.
 * @param namespace   Namespace scope. Null only for CLUSTER_ADMIN.
 * @param certSerial  Serial of the mTLS certificate (for revocation).
 * @param createdAt   When this identity was approved.
 * @param revokedAt   Set when cert is revoked. Null if active.
 * @param metadata    Arbitrary labels (team, email, etc.).
 */
public record RiggerIdentity(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("role") RiggerRole role,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("certSerial") String certSerial,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("revokedAt") Instant revokedAt,
        @JsonProperty("metadata") Map<String, String> metadata
) {
    public boolean isActive() {
        return revokedAt == null;
    }

    public boolean isScopedTo(String ns) {
        return role == RiggerRole.CLUSTER_ADMIN || ns.equals(namespace);
    }
}
