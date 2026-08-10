package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** One operational event for the console's activity feed. */
public record EventResponse(
        @JsonProperty("id")           String id,
        @JsonProperty("type")         String type,
        @JsonProperty("resourceKind") String resourceKind,
        @JsonProperty("resourceName") String resourceName,
        @JsonProperty("namespace")    String namespace,
        @JsonProperty("actor")        String actor,
        @JsonProperty("message")      String message,
        @JsonProperty("occurredAt")   Instant occurredAt
) {}
