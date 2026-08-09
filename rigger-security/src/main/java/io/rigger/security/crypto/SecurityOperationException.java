package io.rigger.security.crypto;

/** Thrown when a cryptographic operation fails. */
public class SecurityOperationException extends RuntimeException {
    public SecurityOperationException(String message) { super(message); }
    public SecurityOperationException(String message, Throwable cause) { super(message, cause); }
}
