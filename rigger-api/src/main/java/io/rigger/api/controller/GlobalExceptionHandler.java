package io.rigger.api.controller;

import io.rigger.api.dto.ErrorResponse;
import io.rigger.core.exception.*;
import io.rigger.core.util.UlidGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps domain exceptions to RFC 7807 Problem Details responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> denied(AccessDeniedException e, HttpServletRequest req) {
        return ResponseEntity.status(403)
            .body(ErrorResponse.of(403, "Forbidden", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(ErrorResponse.of(404, "Not Found", e.getMessage(), req.getRequestURI()));
    }

    /**
     * A request that matched no mapping. Without this it fell through to the generic handler and
     * came back as a 500 with a correlation ID — so a client typo looked like a server fault, and
     * every mistyped URL logged a stack trace as if something had broken.
     *
     * <p>The URI is already in the response; the exception message adds nothing, so it is not used.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noHandler(NoResourceFoundException e, HttpServletRequest req) {
        return ResponseEntity.status(404)
            .body(ErrorResponse.of(404, "Not Found", "No endpoint for this request", req.getRequestURI()));
    }

    /**
     * An unparseable or wrongly-typed request body. Also a 500 before this, which pointed the
     * caller at a server fault when the body was theirs to fix.
     *
     * <p>Only the most specific message is returned — the full Jackson message names internal
     * classes and deserialisation features, which is server detail, so the rest is logged instead.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e, HttpServletRequest req) {
        log.debug("Unreadable request body on {}: {}", req.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(400, "Bad Request", "Request body is malformed or has a wrong field type",
                req.getRequestURI()));
    }

    @ExceptionHandler(ManifestValidationException.class)
    public ResponseEntity<ErrorResponse> validation(ManifestValidationException e, HttpServletRequest req) {
        return ResponseEntity.status(422)
            .body(ErrorResponse.of(422, "Unprocessable Entity",
                String.join("; ", e.violations()), req.getRequestURI()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(InvalidRequestException e, HttpServletRequest req) {
        // The message is authored by us and names the offending parameter, so returning it is both
        // safe and the only way the caller can tell a typo from a genuinely empty result.
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(400, "Bad Request", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<ErrorResponse> provisioning(ProvisioningException e, HttpServletRequest req) {
        // An expected operational failure (unreachable node, SSH/Docker install error) —
        // its message is intentional and safe to return, unlike an uncaught exception.
        return ResponseEntity.status(502)
            .body(ErrorResponse.of(502, "Bad Gateway", e.getMessage(), req.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> generic(Exception e, HttpServletRequest req) {
        // Unlike the handlers above (whose messages are intentional and safe to expose),
        // an uncaught exception's message can leak internals (SQL, file paths, stack frames).
        // Log the real detail server-side and hand the client only a correlation id to quote back.
        String correlationId = UlidGenerator.generate();
        log.error("Unhandled exception [correlationId={}]", correlationId, e);
        return ResponseEntity.status(500)
            .body(ErrorResponse.of(500, "Internal Server Error",
                "An unexpected error occurred. Reference: " + correlationId,
                req.getRequestURI(), correlationId));
    }
}
