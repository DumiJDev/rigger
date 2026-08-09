package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSH connection credentials for a cluster node.
 * Passwords are never supported — key-based auth only.
 *
 * @param user           SSH user on the remote host.
 * @param privateKeyPath Path to the Ed25519 or RSA private key on the local machine.
 * @param port           SSH port (default 22).
 */
public record SshCredentials(
        @JsonProperty("user") String user,
        @JsonProperty("privateKeyPath") String privateKeyPath,
        @JsonProperty("port") int port
) {
    public SshCredentials {
        if (user == null || user.isBlank()) throw new IllegalArgumentException("SSH user must not be blank");
        if (privateKeyPath == null || privateKeyPath.isBlank()) throw new IllegalArgumentException("SSH privateKeyPath must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("SSH port must be between 1 and 65535");
    }

    /** Convenience factory with default port 22. */
    public static SshCredentials of(String user, String privateKeyPath) {
        return new SshCredentials(user, privateKeyPath, 22);
    }
}
