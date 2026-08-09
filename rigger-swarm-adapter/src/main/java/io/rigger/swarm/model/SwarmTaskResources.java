package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** CPU/memory resource limits and reservations for a task. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmTaskResources(
        @JsonProperty("Limits")       SwarmResourceSpec limits,
        @JsonProperty("Reservations") SwarmResourceSpec reservations
) {}
