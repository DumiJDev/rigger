package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Swarm service scheduling mode. Rigger uses Replicated mode only. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmServiceMode(
        @JsonProperty("Replicated") SwarmReplicated replicated
) {}
