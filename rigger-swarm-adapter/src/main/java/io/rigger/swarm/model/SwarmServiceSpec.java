package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Docker Swarm service spec (subset used by Rigger). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmServiceSpec(
        @JsonProperty("Name")         String name,
        @JsonProperty("Labels")       Map<String, String> labels,
        @JsonProperty("TaskTemplate") SwarmTaskTemplate taskTemplate,
        @JsonProperty("Mode")         SwarmServiceMode mode,
        @JsonProperty("UpdateConfig") SwarmUpdateConfig updateConfig,
        @JsonProperty("EndpointSpec") SwarmEndpointSpec endpointSpec
) {}
