package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Service endpoint: exposed ports. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmEndpointSpec(
        @JsonProperty("Mode")  String mode,
        @JsonProperty("Ports") List<SwarmPortConfig> ports
) {}
