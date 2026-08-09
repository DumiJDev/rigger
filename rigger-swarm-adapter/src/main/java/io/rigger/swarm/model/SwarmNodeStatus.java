package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmNodeStatus(
        @JsonProperty("State") String state,
        @JsonProperty("Addr")  String addr
) {}
