package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body for POST /api/v1/users (create or update a user). */
public record UserRequest(
        @JsonProperty("username")  String username,
        @JsonProperty("password")  String password,
        @JsonProperty("role")      String role,
        @JsonProperty("namespace") String namespace
) {}
