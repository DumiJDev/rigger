package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Common metadata attached to every Rigger resource.
 * Equivalent to Kubernetes ObjectMeta — name + namespace + labels + annotations.
 *
 * @param name        Unique resource name within its namespace and kind.
 * @param namespace   Namespace this resource belongs to. Never null or blank.
 * @param labels      Key/value labels for selectors and filtering.
 * @param annotations Arbitrary metadata (not used for selection).
 */
public record ObjectMeta(
        @JsonProperty("name") String name,
        @JsonProperty("namespace") String namespace,
        @JsonProperty("labels") Map<String, String> labels,
        @JsonProperty("annotations") Map<String, String> annotations
) {
    public ObjectMeta {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Resource name must not be blank");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("Namespace must not be blank — Rigger requires explicit namespaces");
        if (labels == null) labels = Map.of();
        if (annotations == null) annotations = Map.of();
    }

    /** Fully qualified identifier: namespace/name */
    public String qualifiedName() {
        return namespace + "/" + name;
    }
}
