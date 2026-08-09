package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A published port in a Swarm service endpoint. */
public record SwarmPortConfig(
        @JsonProperty("Protocol")      String protocol,
        @JsonProperty("TargetPort")    int targetPort,
        @JsonProperty("PublishedPort") int publishedPort,
        @JsonProperty("PublishMode")   String publishMode
) {}
