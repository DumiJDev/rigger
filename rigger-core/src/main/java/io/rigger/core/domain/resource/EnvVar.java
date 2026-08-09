package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An environment variable definition for a container.
 * Either a literal value or a reference to a ConfigMap or Secret key.
 *
 * @param name       Environment variable name.
 * @param value      Literal value (mutually exclusive with valueFrom).
 * @param valueFrom  Reference to an external source.
 */
public record EnvVar(
        @JsonProperty("name") String name,
        @JsonProperty("value") String value,
        @JsonProperty("valueFrom") EnvVarSource valueFrom
) {
    public EnvVar {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("EnvVar name must not be blank");
        if (value != null && valueFrom != null)
            throw new IllegalArgumentException("EnvVar cannot have both value and valueFrom");
    }
}
