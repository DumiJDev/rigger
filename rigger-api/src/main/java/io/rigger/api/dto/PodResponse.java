package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A Swarm task, presented as a Kubernetes-style "pod" for CLI/UI familiarity. */
public record PodResponse(
        @JsonProperty("name")       String name,
        @JsonProperty("namespace")  String namespace,
        @JsonProperty("deployment") String deployment,
        @JsonProperty("image")      String image,
        @JsonProperty("node")       String node,
        @JsonProperty("state")      String state,
        @JsonProperty("desiredState") String desiredState,
        @JsonProperty("message")    String message,
        @JsonProperty("createdAt")  String createdAt
) {}
