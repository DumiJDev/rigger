package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * GitOps configuration submitted from the console.
 *
 * <p>Two auth modes: {@code sshKeyPath} points at a key that must already exist on the server (no
 * key material crosses this endpoint), or {@code httpsUsername}/{@code httpsToken} for HTTPS —
 * the token is encrypted (AES-256-GCM) before being persisted. A blank {@code httpsToken} means
 * "keep the currently stored token", so the console never has to re-submit it on every save.
 */
public record GitOpsConfigRequest(
        @JsonProperty("enabled")             boolean enabled,
        @JsonProperty("repositoryUrl")       String repositoryUrl,
        @JsonProperty("branch")              String branch,
        @JsonProperty("sshKeyPath")          String sshKeyPath,
        @JsonProperty("authType")            String authType,
        @JsonProperty("httpsUsername")       String httpsUsername,
        @JsonProperty("httpsToken")          String httpsToken,
        @JsonProperty("pollIntervalSeconds") int pollIntervalSeconds,
        @JsonProperty("manifestPaths")       List<String> manifestPaths,
        @JsonProperty("namespaceMapping")    Map<String, String> namespaceMapping
) {}
