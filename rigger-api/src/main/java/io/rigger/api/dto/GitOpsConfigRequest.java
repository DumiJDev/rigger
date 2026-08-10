package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * GitOps configuration submitted from the console.
 *
 * <p>Carries no credentials by design — {@code sshKeyPath} points at a key that must already exist
 * on the server. Accepting key material over this endpoint would mean storing it in the database
 * with no encryption path.
 */
public record GitOpsConfigRequest(
        @JsonProperty("enabled")             boolean enabled,
        @JsonProperty("repositoryUrl")       String repositoryUrl,
        @JsonProperty("branch")              String branch,
        @JsonProperty("sshKeyPath")          String sshKeyPath,
        @JsonProperty("pollIntervalSeconds") int pollIntervalSeconds,
        @JsonProperty("manifestPaths")       List<String> manifestPaths,
        @JsonProperty("namespaceMapping")    Map<String, String> namespaceMapping
) {}
