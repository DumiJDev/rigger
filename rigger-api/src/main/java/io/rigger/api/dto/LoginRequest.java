package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body for POST /api/v1/auth/login
 * Simple username+password for first login.
 * On success, the server returns a JWT.
 */
public record LoginRequest(
        @JsonProperty("username") String username,
        @JsonProperty("password") String password
) {}
