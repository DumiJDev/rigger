package io.rigger.core.domain.cluster;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Root object parsed from {@code rigger.cluster.yaml}.
 * This is the single source of truth for cluster infrastructure.
 *
 * <p>Example YAML:
 * <pre>
 * apiVersion: rigger.io/v1
 * kind: Cluster
 * metadata:
 *   name: prod-angola
 * spec:
 *   docker:
 *     version: "26.1"
 *     channel: stable
 *   defaults:
 *     ssh:
 *       user: ubuntu
 *       privateKeyPath: ~/.ssh/rigger_id_ed25519
 *   nodes:
 *     - name: manager-01
 *       ip: 10.0.0.10
 *       role: manager
 *       primary: true
 * </pre>
 *
 * @param name     Cluster name (used in audit logs and UI).
 * @param region   Optional geographic region label.
 * @param docker   Docker install spec applied to nodes missing Docker.
 * @param defaults Default SSH credentials (overridden per node).
 * @param nodes    Ordered list of node declarations.
 * @param dev      Dev-mode configuration.
 * @param labels   Cluster-level labels.
 */
public record ClusterSpec(
        @JsonProperty("name") String name,
        @JsonProperty("region") String region,
        @JsonProperty("docker") DockerSpec docker,
        @JsonProperty("defaults") ClusterDefaults defaults,
        @JsonProperty("nodes") List<NodeSpec> nodes,
        @JsonProperty("dev") DevMode dev,
        @JsonProperty("labels") Map<String, String> labels
) {
    public ClusterSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Cluster name must not be blank");
        if (nodes == null || nodes.isEmpty()) throw new IllegalArgumentException("Cluster must declare at least one node");
        if (docker == null) docker = DockerSpec.DEFAULT;
        if (dev == null) dev = DevMode.DISABLED;
        if (labels == null) labels = Map.of();

        long primaryCount = nodes.stream().filter(NodeSpec::primary).count();
        if (!dev.enabled() && primaryCount != 1)
            throw new IllegalArgumentException("Exactly one node must have primary: true (found " + primaryCount + ")");
    }

    /** Resolve effective SSH credentials for a node (node-level overrides defaults). */
    public SshCredentials resolveCredentials(NodeSpec node) {
        return node.ssh() != null ? node.ssh() : defaults().ssh();
    }

    /** Returns the primary manager node. */
    public NodeSpec primaryNode() {
        return nodes.stream().filter(NodeSpec::primary).findFirst()
                .orElseThrow(() -> new IllegalStateException("No primary node found"));
    }
}
