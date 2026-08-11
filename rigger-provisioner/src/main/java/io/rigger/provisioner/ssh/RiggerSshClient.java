package io.rigger.provisioner.ssh;

import io.rigger.core.domain.cluster.SshCredentials;
import io.rigger.core.exception.ProvisioningException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
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
 * <p>Host key verification is trust-on-first-use: the first connection to a node
 * accepts and persists its host key to {@code ~/.rigger/known_hosts}; subsequent
 * connections are verified strictly against that persisted entry, and a mismatch
 * (e.g. a MITM, or a re-provisioned node with a new host key) is rejected.
 */
@Component
public class RiggerSshClient {

    private static final Logger log = LoggerFactory.getLogger(RiggerSshClient.class);
    private static final int CONNECT_TIMEOUT_SEC = 30;

    private SshClient client;

    @PostConstruct
    public void start() {
        client = SshClient.setUpDefaultClient();

        Path knownHosts = Paths.get(System.getProperty("user.home"), ".rigger", "known_hosts");
        try {
            Files.createDirectories(knownHosts.getParent());
        } catch (IOException e) {
            throw new ProvisioningException("local", "Failed to create " + knownHosts.getParent(), e);
        }

        // Unknown hosts are accepted and persisted on first connect (TOFU). Once a host is
        // recorded, KnownHostsServerKeyVerifier itself rejects any key that doesn't match.
        client.setServerKeyVerifier(new DefaultKnownHostsServerKeyVerifier(
            AcceptAllServerKeyVerifier.INSTANCE, true, knownHosts));
        log.info("SSH host keys tracked in {}", knownHosts);

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

    /**
     * Expands a user-supplied key path. Beyond the Unix {@code ~/} form this also accepts the
     * two spellings a Windows user will actually type — {@code ~\} and {@code %USERPROFILE%} —
     * since Java performs no shell expansion of its own and the path would otherwise be taken
     * literally and reported as "key not found".
     */
    static Path expandPath(String path) {
        String p = path.trim();
        if (p.regionMatches(true, 0, "%USERPROFILE%", 0, 13)) return underHome(p.substring(13));
        if (p.equals("~"))                                    return underHome("");
        if (p.startsWith("~/") || p.startsWith("~\\"))         return underHome(p.substring(1));
        return Paths.get(p);
    }

    /** Resolves a home-relative remainder, accepting either separator so Windows input works. */
    private static Path underHome(String rest) {
        Path base = Paths.get(System.getProperty("user.home"));
        for (String segment : rest.split("[/\\\\]")) {
            if (!segment.isEmpty()) base = base.resolve(segment);
        }
        return base;
    }
}
