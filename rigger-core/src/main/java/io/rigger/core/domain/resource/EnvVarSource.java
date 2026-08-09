package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference to a ConfigMap or Secret key as the source of an env variable.
 *
 * @param configMapKeyRef Reference to a ConfigMap key.
 * @param secretKeyRef    Reference to a Secret key (value is never logged).
 */
public record EnvVarSource(
        @JsonProperty("configMapKeyRef") KeyRef configMapKeyRef,
        @JsonProperty("secretKeyRef") KeyRef secretKeyRef
) {
    public EnvVarSource {
        if (configMapKeyRef != null && secretKeyRef != null)
            throw new IllegalArgumentException("EnvVarSource cannot reference both ConfigMap and Secret");
    }
}
