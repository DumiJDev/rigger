package io.rigger.provisioner.docker;

import io.rigger.provisioner.ssh.SshSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects whether Docker Engine is installed on a remote node,
 * and identifies the Linux distribution family.
 */
@Component
public class DockerDetector {

    private static final Logger log = LoggerFactory.getLogger(DockerDetector.class);

    /**
     * Returns true if Docker Engine is installed and the daemon is running.
     * Checks both the binary and the socket.
     */
    public boolean isDockerInstalled(SshSession session) {
        var result = session.exec("docker --version 2>/dev/null && docker info --format '{{.ServerVersion}}' 2>/dev/null");
        boolean installed = result.isSuccess() && !result.trimmedOutput().isEmpty();
        log.debug("Docker installed on {}: {} ({})", session.remoteHost(), installed, result.trimmedOutput());
        return installed;
    }

    /**
     * Returns the installed Docker Engine version string, or null if not installed.
     * Example: "26.1.4"
     */
    public String installedVersion(SshSession session) {
        var result = session.exec("docker info --format '{{.ServerVersion}}' 2>/dev/null");
        return result.isSuccess() ? result.trimmedOutput() : null;
    }

    /**
     * Detects the Linux distribution family on the remote node.
     * Used by DockerInstaller to pick the correct package manager commands.
     */
    public LinuxDistro detectDistro(SshSession session) {
        var result = session.exec("cat /etc/os-release 2>/dev/null || cat /etc/redhat-release 2>/dev/null");
        if (!result.isSuccess()) return LinuxDistro.UNKNOWN;

        String out = result.trimmedOutput().toLowerCase();
        if (out.contains("ubuntu") || out.contains("debian") || out.contains("raspbian")) {
            return LinuxDistro.DEBIAN;
        }
        if (out.contains("rhel") || out.contains("centos") || out.contains("rocky")
                || out.contains("alma") || out.contains("fedora")) {
            return LinuxDistro.RHEL;
        }
        return LinuxDistro.UNKNOWN;
    }
}
