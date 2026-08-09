package io.rigger.provisioner.swarm;

/**
 * Docker Swarm join tokens returned by {@code docker swarm init}.
 * The worker token is used to join worker nodes.
 * The manager token is used to join additional manager nodes.
 *
 * SECURITY: tokens are treated as secrets — never logged at INFO level.
 */
public record SwarmTokens(
        String managerToken,
        String workerToken,
        String managerAdvertiseAddr
) {
    public SwarmTokens {
        if (managerToken == null || managerToken.isBlank())
            throw new IllegalArgumentException("Swarm manager token must not be blank");
        if (workerToken == null || workerToken.isBlank())
            throw new IllegalArgumentException("Swarm worker token must not be blank");
    }
}
