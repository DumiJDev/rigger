package io.rigger.provisioner.ssh;

/**
 * Result of a command executed over SSH on a remote node.
 *
 * @param exitCode   Process exit code (0 = success).
 * @param stdout     Standard output captured as a string.
 * @param stderr     Standard error captured as a string.
 * @param command    The original command (for logging/debugging).
 */
public record SshCommandResult(
        int exitCode,
        String stdout,
        String stderr,
        String command
) {
    public boolean isSuccess() { return exitCode == 0; }

    public String trimmedOutput() { return stdout == null ? "" : stdout.trim(); }

    @Override
    public String toString() {
        return "SshCommandResult{exit=" + exitCode + ", cmd=\"" + command + "\"}";
    }
}
