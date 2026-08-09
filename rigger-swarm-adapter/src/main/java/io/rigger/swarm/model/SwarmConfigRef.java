package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Reference to a Docker Config mounted inside a container. */
public record SwarmConfigRef(
        @JsonProperty("ConfigID")   String configId,
        @JsonProperty("ConfigName") String configName,
        @JsonProperty("File")       SwarmConfigFile file
) {}
