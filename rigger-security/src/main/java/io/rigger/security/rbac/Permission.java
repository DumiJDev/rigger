package io.rigger.security.rbac;

import io.rigger.core.domain.security.RiggerRole;

/**
 * A permission tuple: which role may perform which action on which resource.
 * The policy table in {@link RbacPolicyEngine} is built from these.
 */
public record Permission(
        RiggerRole role,
        String action,
        String resource
) {
    public static Permission of(RiggerRole role, String action, String resource) {
        return new Permission(role, action, resource);
    }
}
