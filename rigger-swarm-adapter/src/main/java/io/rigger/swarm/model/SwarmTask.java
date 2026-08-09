package io.rigger.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A Docker Swarm task — equivalent to a Rigger Pod.
 * Returned by GET /tasks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SwarmTask(
        @JsonProperty("ID")          String id,
        @JsonProperty("ServiceID")   String serviceId,
        @JsonProperty("NodeID")      String nodeId,
        @JsonProperty("Slot")        int slot,
        @JsonProperty("Status")      SwarmTaskStatus status,
        @JsonProperty("Spec")        SwarmTaskTemplate spec,
        @JsonProperty("CreatedAt")   Instant createdAt
) {}
