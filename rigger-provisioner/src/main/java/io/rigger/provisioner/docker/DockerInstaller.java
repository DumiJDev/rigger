package io.rigger.provisioner.docker;

import io.rigger.core.domain.cluster.DockerSpec;
import io.rigger.core.exception.ProvisioningException;
import io.rigger.provisioner.ssh.SshSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Installs Docker Engine on a remote node via SSH.
 *
 * <p>Installation strategy by distro:
 * <ul>
 *   <li><b>DEBIAN</b>: apt-get + Docker's official apt repository</li>
 *   <li><b>RHEL</b>: dnf/yum + Docker's official rpm repository</li>
 *   <li><b>UNKNOWN</b>: Docker's convenience install script (get.docker.com)</li>
 * </ul>
 *
 * <p>After installation, Docker daemon is started and enabled for auto-start.
 * The SSH user is added to the docker group to allow non-root usage.
 */
@Component
public class DockerInstaller {

    private static final Logger log = LoggerFactory.getLogger(DockerInstaller.class);

    private final DockerDetector detector;

    public DockerInstaller(DockerDetector detector) {
        this.detector = detector;
    }

    /**
     * Installs Docker on the remote node if not already present.
     * Idempotent — safe to call on a node that already has Docker.
     *
     * @param session SSH session to the remote node.
     * @param spec    Docker version/channel spec.
     * @param nodeName Node name for error messages.
     * @return true if Docker was installed by this call, false if already present.
     */
    public boolean installIfAbsent(SshSession session, DockerSpec spec, String nodeName) {
        if (detector.isDockerInstalled(session)) {
            String version = detector.installedVersion(session);
            log.info("Docker already installed on {} (version {})", nodeName, version);
            return false;
        }

        log.info("Installing Docker {} ({}) on {}", spec.version(), spec.channel(), nodeName);
        var distro = detector.detectDistro(session);
        log.info("Detected distro on {}: {}", nodeName, distro);

        switch (distro) {
            case DEBIAN -> installDebian(session, spec, nodeName);
            case RHEL   -> installRhel(session, spec, nodeName);
            default     -> installScript(session, nodeName);
        }

        startAndEnableDaemon(session, nodeName);
        addUserToDockerGroup(session, nodeName);
        verifyInstallation(session, nodeName);

        log.info("Docker installed successfully on {}", nodeName);
        return true;
    }

    private void installDebian(SshSession session, DockerSpec spec, String nodeName) {
        execOrFail(session, nodeName, "apt-get update -qq");
        execOrFail(session, nodeName, "apt-get install -y -qq ca-certificates curl gnupg lsb-release");
        execOrFail(session, nodeName, "install -m 0755 -d /etc/apt/keyrings");
        execOrFail(session, nodeName,
            "curl -fsSL https://download.docker.com/linux/$(. /etc/os-release && echo $ID)/gpg " +
            "| gpg --dearmor -o /etc/apt/keyrings/docker.gpg && chmod a+r /etc/apt/keyrings/docker.gpg");
        execOrFail(session, nodeName,
            "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] " +
            "https://download.docker.com/linux/$(. /etc/os-release && echo $ID) " +
            "$(. /etc/os-release && echo $VERSION_CODENAME) " + spec.channel() + "\" " +
            "| tee /etc/apt/sources.list.d/docker.list > /dev/null");
        execOrFail(session, nodeName, "apt-get update -qq");
        execOrFail(session, nodeName, "apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin");
    }

    private void installRhel(SshSession session, DockerSpec spec, String nodeName) {
        execOrFail(session, nodeName, "dnf install -y -q dnf-plugins-core 2>/dev/null || yum install -y -q yum-utils");
        execOrFail(session, nodeName,
            "dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo 2>/dev/null || " +
            "yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo");
        execOrFail(session, nodeName,
            "dnf install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin 2>/dev/null || " +
            "yum install -y -q docker-ce docker-ce-cli containerd.io");
    }

    private void installScript(SshSession session, String nodeName) {
        log.warn("Using Docker convenience script on {} — distro not recognised", nodeName);
        execOrFail(session, nodeName, "curl -fsSL https://get.docker.com | sh");
    }

    private void startAndEnableDaemon(SshSession session, String nodeName) {
        execOrFail(session, nodeName, "systemctl start docker");
        execOrFail(session, nodeName, "systemctl enable docker");
    }

    private void addUserToDockerGroup(SshSession session, String nodeName) {
        // Get the SSH user's name from the session
        var whoami = session.exec("whoami");
        if (whoami.isSuccess()) {
            String user = whoami.trimmedOutput();
            session.sudo("usermod -aG docker " + user);
        }
    }

    private void verifyInstallation(SshSession session, String nodeName) {
        var result = session.exec("docker --version");
        if (!result.isSuccess()) {
            throw new ProvisioningException(nodeName,
                "Docker installation completed but verification failed: " + result.stderr());
        }
        log.info("Docker version on {}: {}", nodeName, result.trimmedOutput());
    }

    private void execOrFail(SshSession session, String nodeName, String command) {
        var result = session.sudo(command);
        if (!result.isSuccess()) {
            throw new ProvisioningException(nodeName,
                "Docker install step failed. Command: " + command +
                " | stderr: " + result.stderr());
        }
    }
}
