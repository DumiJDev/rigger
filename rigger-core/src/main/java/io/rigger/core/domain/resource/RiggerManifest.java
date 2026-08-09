package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Envelope for every Rigger manifest YAML file.
 * Mirrors the Kubernetes manifest structure: apiVersion, kind, metadata, spec.
 *
 * <p>Example:
 * <pre>
 * apiVersion: rigger.io/v1
 * kind: Deployment
 * metadata:
 *   name: payments-api
 *   namespace: production
 * spec:
 *   replicas: 3
 *   ...
 * </pre>
 *
 * @param apiVersion Must be "rigger.io/v1".
 * @param kind       Resource kind (Deployment, Service, Secret, etc.).
 * @param metadata   Name, namespace, labels.
 * @param spec       Kind-specific spec object (deserialized by ManifestParser).
 */
public record RiggerManifest(
        @JsonProperty("apiVersion") String apiVersion,
        @JsonProperty("kind") String kind,
        @JsonProperty("metadata") ObjectMeta metadata,
        @JsonProperty("spec") Object spec
) {
    public static final String API_VERSION = "rigger.io/v1";

    public RiggerManifest {
        if (!API_VERSION.equals(apiVersion))
            throw new IllegalArgumentException("Unsupported apiVersion: " + apiVersion + " (expected " + API_VERSION + ")");
        if (kind == null || kind.isBlank())
            throw new IllegalArgumentException("kind must not be blank");
        if (metadata == null)
            throw new IllegalArgumentException("metadata must not be null");
    }
}
