package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A namespace's workload graph, derived server-side so the console doesn't have to re-implement
 * selector matching and reference resolution in TypeScript.
 *
 * <p>Nodes are Deployments plus the Services/ConfigMaps/Secrets attached to them; edges are the
 * relationships between them ({@code exposes} for a Service whose selector matches a Deployment,
 * {@code mounts} for a ConfigMap/Secret named in the Deployment's refs).
 */
public record TopologyResponse(
        @JsonProperty("namespace") String namespace,
        @JsonProperty("nodes")     List<Node> nodes,
        @JsonProperty("edges")     List<Edge> edges
) {
    /**
     * @param health one of {@code healthy} (all replicas running), {@code degraded} (some),
     *               {@code down} (none running but some desired), {@code unknown} (not yet
     *               reconciled onto Swarm), or {@code n/a} for non-Deployment nodes.
     */
    public record Node(
            @JsonProperty("id")              String id,
            @JsonProperty("kind")            String kind,
            @JsonProperty("name")            String name,
            @JsonProperty("image")           String image,
            @JsonProperty("desiredReplicas") Integer desiredReplicas,
            @JsonProperty("runningReplicas") Integer runningReplicas,
            @JsonProperty("health")          String health,
            @JsonProperty("hpaEnabled")      boolean hpaEnabled
    ) {}

    public record Edge(
            @JsonProperty("from") String from,
            @JsonProperty("to")   String to,
            @JsonProperty("type") String type
    ) {}
}
