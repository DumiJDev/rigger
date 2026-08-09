package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserResponse(
        @JsonProperty("username")  String username,
        @JsonProperty("role")      String role,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("active")    boolean active
) {}
