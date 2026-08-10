package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Read-only view of the GitOps agent's last sync attempt. */
public record GitOpsStateResponse(
        @JsonProperty("enabled")           boolean enabled,
        @JsonProperty("repositoryUrl")     String repositoryUrl,
        @JsonProperty("branch")            String branch,
        @JsonProperty("lastAppliedCommit") String lastAppliedCommit,
        @JsonProperty("lastAppliedAt")     Instant lastAppliedAt,
        @JsonProperty("result")            String result,
        @JsonProperty("errorMessage")      String errorMessage
) {}
