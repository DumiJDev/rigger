package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SwarmPlatform(
        @JsonProperty("Architecture") String architecture,
        @JsonProperty("OS")           String os
) {}
