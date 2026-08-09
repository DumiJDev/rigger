package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Single-node development mode configuration.
 * When enabled, Rigger initialises a local Swarm on the Docker socket
 * instead of provisioning remote nodes. HA, node autoscaling and
 * mTLS provisioning are disabled in dev mode.
 *
 * @param enabled      Whether dev mode is active.
 * @param dockerSocket Path to the local Docker socket.
 */
public record DevMode(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("dockerSocket") String dockerSocket
) {
    public static final DevMode DISABLED = new DevMode(false, "/var/run/docker.sock");

    public DevMode {
        if (dockerSocket == null || dockerSocket.isBlank()) dockerSocket = "/var/run/docker.sock";
    }
}
