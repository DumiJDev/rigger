package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Spec for a Secret resource.
 * Values are base64-encoded in the YAML and AES-256-GCM encrypted at rest.
 * Secret values are NEVER written to logs or the audit log.
 *
 * @param data      Map of key → base64-encoded value (as declared in YAML).
 * @param vaultRef  Optional reference to an external HashiCorp Vault path.
 */
public record SecretSpec(
        @JsonProperty("data") Map<String, String> data,
        @JsonProperty("vaultRef") String vaultRef
) {
    public SecretSpec {
        if ((data == null || data.isEmpty()) && (vaultRef == null || vaultRef.isBlank()))
            throw new IllegalArgumentException("Secret must declare either data or vaultRef");
        if (data == null) data = Map.of();
    }
}
