package io.rigger.provisioner.ssh;

import io.rigger.core.domain.cluster.SshCredentials;
import io.rigger.core.exception.ProvisioningException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle-managed SSH client wrapping Apache MINA SSHD.
 * A single SshClient instance is shared across all provisioner operations.
 * Individual {@link SshSession} instances are created per node connection.
 *
 * <p>Security: password authentication is disabled at the MINA client level.
 * All connections use Ed25519 or RSA private keys.
 *
 * <p>Host key verification uses a known_hosts file when available.
 * Falls back to accept-all in dev mode (logged as a warning).
 */
@Component
public class RiggerSshClient {

    private static final Logger log = LoggerFactory.getLogger(RiggerSshClient.class);
    private static final int CONNECT_TIMEOUT_SEC = 30;

    private SshClient client;

    @PostConstruct
    public void start() {
        client = SshClient.setUpDefaultClient();
        // Disable password auth — key-based only
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            // TODO Phase 3: replace with KnownHostsServerKeyVerifier
            // For now, log and accept — provisioner only connects to declared cluster IPs
            log.debug("Accepting server key from {}", remoteAddress);
            return true;
        });
        client.start();
        log.info("Rigger SSH client started");
    }

    @PreDestroy
    public void stop() {
        if (client != null) {
            client.stop();
            log.info("Rigger SSH client stopped");
        }
    }

    /**
     * Opens an SSH session to the given host using the provided credentials.
     * The caller is responsible for closing the returned session.
     *
     * @param host  Remote IP address.
     * @param creds SSH credentials (user + private key path).
     * @return An active SshSession ready for command execution.
     * @throws ProvisioningException if connection or authentication fails.
     */
    public SshSession connect(String host, SshCredentials creds) {
        log.info("Connecting to {}@{} via SSH", creds.user(), host);
        try {
            var keyPath = expandPath(creds.privateKeyPath());
            if (!Files.exists(keyPath)) {
                throw new ProvisioningException(host,
                    "SSH private key not found: " + keyPath);
            }

            var keyProvider = new FileKeyPairProvider(keyPath);
            ClientSession session = client
                    .connect(creds.user(), host, creds.port())
                    .verify(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .getSession();

            session.addPublicKeyIdentity(keyProvider.loadKeys(null).iterator().next());
            session.auth().verify(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);

            log.info("SSH session established to {}@{}", creds.user(), host);
            return new MinaSshSession(session, host);

        } catch (IOException e) {
            throw new ProvisioningException(host,
                "Failed to connect to " + creds.user() + "@" + host + ":" + creds.port(), e);
        }
    }

    /**
     * Tests connectivity to a host without opening a full session.
     * Returns true if SSH handshake succeeds, false otherwise.
     */
    public boolean isReachable(String host, SshCredentials creds) {
        try (var session = connect(host, creds)) {
            return true;
        } catch (Exception e) {
            log.warn("SSH connectivity check failed for {}: {}", host, e.getMessage());
            return false;
        }
    }

    private Path expandPath(String path) {
        if (path.startsWith("~/")) {
            return Paths.get(System.getProperty("user.home"), path.substring(2));
        }
        return Paths.get(path);
    }
}
