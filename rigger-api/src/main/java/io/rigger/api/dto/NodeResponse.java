package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.rigger.core.domain.cluster.*;
import java.time.Instant;

/** Cluster node status as returned by GET /cluster/nodes. */
public record NodeResponse(
        @JsonProperty("name")       String name,
        @JsonProperty("ip")         String ip,
        @JsonProperty("role")       NodeRole role,
        @JsonProperty("status")     NodeStatus status,
        @JsonProperty("primary")    boolean primary,
        @JsonProperty("swarmNodeId")String swarmNodeId,
        @JsonProperty("lastSeenAt") Instant lastSeenAt
) {}
