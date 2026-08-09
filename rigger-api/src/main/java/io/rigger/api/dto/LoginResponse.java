package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from POST /api/v1/auth/login
 * The token must be sent as: Authorization: Bearer <token>
 */
public record LoginResponse(
        @JsonProperty("token")     String token,
        @JsonProperty("username")  String username,
        @JsonProperty("role")      String role,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("expiresIn") int expiresInSeconds
) {}
