package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Service metadata from Docker API (version, creation time). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmMeta(
        @JsonProperty("Version")   SwarmVersion version,
        @JsonProperty("CreatedAt") Instant createdAt,
        @JsonProperty("UpdatedAt") Instant updatedAt
) {}
