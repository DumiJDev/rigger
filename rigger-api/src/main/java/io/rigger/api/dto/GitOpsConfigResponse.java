package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Effective GitOps configuration.
 *
 * @param source {@code "database"} when an operator has saved config through the console,
 *               {@code "properties"} when it's still coming from environment/YAML. Surfaced so the
 *               console can tell the user their edits will take over from the deploy-time config.
 */
public record GitOpsConfigResponse(
        @JsonProperty("enabled")             boolean enabled,
        @JsonProperty("repositoryUrl")       String repositoryUrl,
        @JsonProperty("branch")              String branch,
        @JsonProperty("sshKeyPath")          String sshKeyPath,
        @JsonProperty("pollIntervalSeconds") int pollIntervalSeconds,
        @JsonProperty("manifestPaths")       List<String> manifestPaths,
        @JsonProperty("namespaceMapping")    Map<String, String> namespaceMapping,
        @JsonProperty("source")              String source,
        @JsonProperty("updatedAt")           Instant updatedAt,
        @JsonProperty("updatedBy")           String updatedBy
) {}
