package io.rigger.core.exception;

/**
 * A request that is well-formed but asks for something that cannot exist — an unknown metric name,
 * an out-of-range window.
 *
 * <p>Distinct from {@link ManifestValidationException}, which is specifically about a manifest
 * failing schema or domain validation and reports a list of violations. This is for query and path
 * parameters, and carries a single message authored here, so it is safe to return verbatim to the
 * caller unlike an uncaught exception.
 */
public class InvalidRequestException extends RiggerException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
