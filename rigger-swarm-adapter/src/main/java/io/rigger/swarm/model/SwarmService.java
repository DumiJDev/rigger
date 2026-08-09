package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Represents a Docker Swarm service as returned by the Docker API.
 * Only fields used by the Rigger reconciler are mapped — unknown fields are ignored.
 *
 * <p>Maps from Docker API response: GET /services or GET /services/{id}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmService(
        @JsonProperty("ID")   String id,
        @JsonProperty("Spec") SwarmServiceSpec spec,
        @JsonProperty("Meta") SwarmMeta meta
) {
    /** Extracts the Rigger namespace label from the service labels. */
    public String riggerNamespace() {
        if (spec == null || spec.labels() == null) return null;
        return spec.labels().get("rigger.io/namespace");
    }

    /** Extracts the Rigger resource name label. */
    public String riggerName() {
        if (spec == null || spec.labels() == null) return null;
        return spec.labels().get("rigger.io/name");
    }
}
