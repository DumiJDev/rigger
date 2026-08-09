package io.rigger.security.rbac;

import java.lang.annotation.*;

/**
 * Method-level security annotation for Rigger RBAC.
 * Applied to service methods to declare required permissions.
 *
 * <p>Usage:
 * <pre>
 * {@literal @}RiggerAuthorize(action = "apply", resource = "Deployment")
 * public void applyDeployment(RiggerManifest manifest, RiggerContext ctx) { ... }
 * </pre>
 *
 * <p>The {@link RbacPolicyEngine} evaluates:
 * <ol>
 *   <li>Is the identity's role allowed to perform {@code action} on {@code resource}?</li>
 *   <li>Is the identity scoped to the namespace in the {@link io.rigger.core.domain.security.RiggerContext}?</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RiggerAuthorize {
    /** The action being performed: apply | delete | scale | get | logs | node_add | user_approve */
    String action();
    /** The resource kind: Deployment | Service | Secret | ConfigMap | Node | User | Cluster */
    String resource();
}
