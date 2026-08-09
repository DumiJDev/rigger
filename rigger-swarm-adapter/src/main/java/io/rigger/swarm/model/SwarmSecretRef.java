package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Reference to a Docker Secret mounted inside a container. */
public record SwarmSecretRef(
        @JsonProperty("SecretID")   String secretId,
        @JsonProperty("SecretName") String secretName,
        @JsonProperty("File")       SwarmSecretFile file
) {}
