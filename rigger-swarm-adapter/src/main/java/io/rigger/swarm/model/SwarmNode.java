package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A Docker Swarm node as returned by GET /nodes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmNode(
        @JsonProperty("ID")          String id,
        @JsonProperty("Description") SwarmNodeDescription description,
        @JsonProperty("Status")      SwarmNodeStatus status,
        @JsonProperty("Spec")        SwarmNodeSpec spec
) {
    public String hostname() {
        return description != null ? description.hostname() : null;
    }
}
