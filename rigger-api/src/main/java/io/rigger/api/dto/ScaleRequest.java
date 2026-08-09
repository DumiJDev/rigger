package io.rigger.api.dto;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for POST /namespaces/{ns}/deployments/{name}/scale. */
public record ScaleRequest(
        @JsonProperty("replicas") int replicas
) {
    public ScaleRequest { if (replicas < 0) throw new IllegalArgumentException("replicas must be >= 0"); }
}
