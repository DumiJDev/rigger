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
 * @param httpsTokenSet whether an HTTPS token is currently stored — the token itself is never
 *                      returned, same principle as Secret resource reads always showing redacted
 *                      values instead of decrypting.
 */
public record GitOpsConfigResponse(
        @JsonProperty("enabled")             boolean enabled,
        @JsonProperty("repositoryUrl")       String repositoryUrl,
        @JsonProperty("branch")              String branch,
        @JsonProperty("sshKeyPath")          String sshKeyPath,
        @JsonProperty("authType")            String authType,
        @JsonProperty("httpsUsername")       String httpsUsername,
        @JsonProperty("httpsTokenSet")       boolean httpsTokenSet,
        @JsonProperty("pollIntervalSeconds") int pollIntervalSeconds,
        @JsonProperty("manifestPaths")       List<String> manifestPaths,
        @JsonProperty("namespaceMapping")    Map<String, String> namespaceMapping,
        @JsonProperty("source")              String source,
        @JsonProperty("updatedAt")           Instant updatedAt,
        @JsonProperty("updatedBy")           String updatedBy
) {}
