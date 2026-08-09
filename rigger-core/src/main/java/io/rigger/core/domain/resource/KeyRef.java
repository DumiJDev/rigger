package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference to a specific key inside a ConfigMap or Secret.
 *
 * @param name Resource name (ConfigMap or Secret).
 * @param key  Key within that resource.
 */
public record KeyRef(
        @JsonProperty("name") String name,
        @JsonProperty("key") String key
) {
    public KeyRef {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("KeyRef name must not be blank");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("KeyRef key must not be blank");
    }
}
