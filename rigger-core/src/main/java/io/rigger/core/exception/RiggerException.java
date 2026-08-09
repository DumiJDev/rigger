package io.rigger.core.exception;

/** Base exception for all Rigger domain errors. */
public class RiggerException extends RuntimeException {
    public RiggerException(String message) { super(message); }
    public RiggerException(String message, Throwable cause) { super(message, cause); }
}
