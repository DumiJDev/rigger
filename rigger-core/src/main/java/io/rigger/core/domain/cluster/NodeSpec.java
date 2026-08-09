package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Desired state of a single node as declared in rigger.cluster.yaml.
 *
 * @param name        Unique node name within the cluster.
 * @param ip          Reachable IP address (SSH + Swarm communication).
 * @param role        MANAGER or WORKER.
 * @param primary     If true, this is the node where {@code docker swarm init} runs.
 *                    Exactly one node in the cluster should have primary=true.
 * @param ssh         Node-level SSH override. Falls back to cluster defaults if null.
 * @param labels      Arbitrary key/value labels for placement constraints.
 * @param autoProvision  If true, Rigger may add this node automatically on scale-up.
 */
public record NodeSpec(
        @JsonProperty("name") String name,
        @JsonProperty("ip") String ip,
        @JsonProperty("role") NodeRole role,
        @JsonProperty("primary") boolean primary,
        @JsonProperty("ssh") SshCredentials ssh,
        @JsonProperty("labels") Map<String, String> labels,
        @JsonProperty("autoProvision") boolean autoProvision
) {
    public NodeSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Node name must not be blank");
        if (ip == null || ip.isBlank()) throw new IllegalArgumentException("Node IP must not be blank");
        if (role == null) throw new IllegalArgumentException("Node role must not be null");
        if (labels == null) labels = Map.of();
    }
}
