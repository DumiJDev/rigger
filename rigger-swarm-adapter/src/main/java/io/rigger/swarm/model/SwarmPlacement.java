package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Task placement constraints (node labels, role filters). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmPlacement(@JsonProperty("Constraints") List<String> constraints) {}
