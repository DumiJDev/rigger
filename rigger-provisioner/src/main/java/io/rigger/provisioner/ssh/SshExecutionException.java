package io.rigger.provisioner.ssh;

import io.rigger.core.exception.ProvisioningException;

/**
 * Thrown when an SSH command fails (non-zero exit or channel error).
 * Extends ProvisioningException so callers can catch at either level.
 */
public class SshExecutionException extends ProvisioningException {

    private final int exitCode;
    private final String stderr;

    public SshExecutionException(String nodeName, String command, int exitCode, String stderr) {
        super(nodeName, "Command \"" + command + "\" failed with exit=" + exitCode
                + (stderr != null && !stderr.isBlank() ? ": " + stderr.trim() : ""));
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public int exitCode() { return exitCode; }
    public String stderr() { return stderr; }
}
