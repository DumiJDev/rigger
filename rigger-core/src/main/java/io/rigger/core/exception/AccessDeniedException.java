package io.rigger.core.exception;

/** Thrown when the RBAC policy engine denies an action. Maps to HTTP 403. */
public class AccessDeniedException extends RiggerException {
    public AccessDeniedException(String identity, String action, String resource) {
        super("Access denied: identity '" + identity + "' cannot perform '" + action + "' on " + resource);
    }
}
