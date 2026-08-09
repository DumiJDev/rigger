package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Container specification inside a Swarm task template. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmContainerSpec(
        @JsonProperty("Image")   String image,
        @JsonProperty("Env")     List<String> env,
        @JsonProperty("Secrets") List<SwarmSecretRef> secrets,
        @JsonProperty("Configs") List<SwarmConfigRef> configs
) {}
