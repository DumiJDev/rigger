package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Default values applied to all nodes unless overridden at node level.
 */
public record ClusterDefaults(
        @JsonProperty("ssh") SshCredentials ssh
) {
    public ClusterDefaults {
        if (ssh == null) throw new IllegalArgumentException("Cluster defaults must include SSH credentials");
    }
}
