package io.rigger.security.rbac;

import io.rigger.core.domain.security.RiggerContext;
import io.rigger.core.exception.AccessDeniedException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Arrays;

/**
 * AOP aspect that enforces {@link RiggerAuthorize} annotations on service methods.
 *
 * <p>When a method annotated with {@code @RiggerAuthorize} is called,
 * this aspect extracts the {@link RiggerContext} from the method arguments
 * and delegates to {@link RbacPolicyEngine#authorize}.
 *
 * <p>The RiggerContext must be the last parameter of the annotated method.
 *
 * <p>Example:
 * <pre>
 * {@literal @}RiggerAuthorize(action = "apply", resource = "Deployment")
 * public void apply(RiggerManifest manifest, RiggerContext ctx) { ... }
 * </pre>
 */
@Aspect
@Component
public class RbacAspect {

    private static final Logger log = LoggerFactory.getLogger(RbacAspect.class);

    private final RbacPolicyEngine policyEngine;

    public RbacAspect(RbacPolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    @Before("@annotation(authorize)")
    public void checkPermission(JoinPoint joinPoint, RiggerAuthorize authorize) {
        // Find RiggerContext in method arguments (always passed as last param by convention)
        var ctx = Arrays.stream(joinPoint.getArgs())
            .filter(arg -> arg instanceof RiggerContext)
            .map(arg -> (RiggerContext) arg)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "@RiggerAuthorize requires a RiggerContext parameter in method: " +
                joinPoint.getSignature().toShortString()));

        policyEngine.authorize(ctx, authorize.action(), authorize.resource());
    }
}
