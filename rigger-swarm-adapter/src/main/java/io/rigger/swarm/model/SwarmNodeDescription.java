package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmNodeDescription(
        @JsonProperty("Hostname") String hostname,
        @JsonProperty("Platform") SwarmPlatform platform
) {}
