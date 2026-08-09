package io.rigger.api.controller;

import io.rigger.api.dto.ErrorResponse;
import io.rigger.core.exception.*;
import io.rigger.core.util.UlidGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

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

    @ExceptionHandler(ManifestValidationException.class)
    public ResponseEntity<ErrorResponse> validation(ManifestValidationException e, HttpServletRequest req) {
        return ResponseEntity.status(422)
            .body(ErrorResponse.of(422, "Unprocessable Entity",
                String.join("; ", e.violations()), req.getRequestURI()));
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
