package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Template for tasks (containers) created by a Swarm service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmTaskTemplate(
        @JsonProperty("ContainerSpec") SwarmContainerSpec containerSpec,
        @JsonProperty("Resources")     SwarmTaskResources resources,
        @JsonProperty("Placement")     SwarmPlacement placement
) {}
