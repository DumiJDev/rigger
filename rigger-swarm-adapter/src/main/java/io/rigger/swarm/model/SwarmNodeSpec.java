package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmNodeSpec(
        @JsonProperty("Role")         String role,
        @JsonProperty("Availability") String availability
) {}
