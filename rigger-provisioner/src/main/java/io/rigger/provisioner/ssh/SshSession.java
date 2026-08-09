package io.rigger.provisioner.ssh;

import java.io.Closeable;

/**
 * Abstraction over an active SSH connection to a remote node.
 * Obtained from {@link RiggerSshClient} and must be closed after use.
 *
 * <p>Implementations use Apache MINA SSHD internally.
 * This interface exists to enable mock implementations in tests
 * without requiring a real SSH server.
 */
public interface SshSession extends Closeable {

    /**
     * Executes a single shell command on the remote host.
     * Blocks until the command completes.
     *
     * @param command Shell command to run (passed to /bin/sh -c).
     * @return Result containing exit code, stdout, and stderr.
     * @throws SshExecutionException if the channel cannot be opened or times out.
     */
    SshCommandResult exec(String command);

    /**
     * Executes a command that requires sudo.
     * Prepends {@code sudo -n } to the command (non-interactive sudo, key-based auth).
     */
    default SshCommandResult sudo(String command) {
        return exec("sudo -n " + command);
    }

    /** Returns the remote host IP this session is connected to. */
    String remoteHost();
}
