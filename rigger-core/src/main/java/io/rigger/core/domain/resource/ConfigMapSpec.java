package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Spec for a ConfigMap resource.
 * Maps to a Docker Config. Values are plain text — use Secret for sensitive data.
 *
 * @param data Key/value pairs stored in the ConfigMap.
 */
public record ConfigMapSpec(
        @JsonProperty("data") Map<String, String> data
) {
    public ConfigMapSpec {
        if (data == null) data = Map.of();
    }
}
