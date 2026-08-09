package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/** Generic resource envelope returned by list and get endpoints. */
public record ResourceResponse(
        @JsonProperty("kind")       String kind,
        @JsonProperty("name")       String name,
        @JsonProperty("namespace")  String namespace,
        @JsonProperty("spec")       Object spec,
        @JsonProperty("labels")     Map<String, String> labels,
        @JsonProperty("appliedBy")  String appliedBy,
        @JsonProperty("createdAt")  Instant createdAt,
        @JsonProperty("updatedAt")  Instant updatedAt
) {}
