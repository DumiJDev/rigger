package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.SecretSpec;
import io.rigger.core.domain.resource.ObjectMeta;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Translates Rigger Secret resources to Docker Secrets using docker-java.
 *
 * SECURITY: Values passed to this adapter must already be AES-256-GCM encrypted
 * by SecretEncryptor. Secret values are never logged.
 */
@Component
public class SecretAdapter {

    private static final Logger log = LoggerFactory.getLogger(SecretAdapter.class);

    private final DockerClientFactory factory;

    public SecretAdapter(DockerClientFactory factory) {
        this.factory = factory;
    }

    private DockerClient docker() { return factory.get(); }

    /**
     * Creates a Docker Secret.
     * @param meta          Resource metadata.
     * @param encryptedData Pre-encrypted values (AES-256-GCM). Never logged.
     */
    public String create(ObjectMeta meta, Map<String, String> encryptedData) {
        // Log only keys, never values
        log.info("Creating Docker Secret: {}/{} (keys: {})", meta.namespace(), meta.name(), encryptedData.keySet());
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(encryptedData);

            var spec = new SecretSpec()
                .withName("rigger-" + meta.namespace() + "-" + meta.name())
                .withLabels(Map.of(
                    "rigger.io/namespace", meta.namespace(),
                    "rigger.io/name",      meta.name(),
                    "rigger.io/kind",      "Secret",
                    "rigger.io/managed",   "true"
                ))
                .withData(Base64.getEncoder().encodeToString(json));

            var response = docker().createSecretCmd(spec).exec();
            return response.getId();
        } catch (Exception e) {
            throw new DockerApiException("Failed to create secret " + meta.qualifiedName(), e);
        }
    }

    /** Lists managed secrets (metadata only — Docker never returns secret values). */
    public List<?> listManaged() {
        try {
            return docker().listSecretsCmd()
                .withLabelFilter(Map.of("rigger.io/managed", "true"))
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list managed secrets", e);
        }
    }

    /** Removes a Docker Secret by ID. */
    public void delete(String secretId) {
        log.info("Deleting Docker Secret: {}", secretId);
        try {
            docker().removeSecretCmd(secretId).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to delete secret " + secretId, e);
        }
    }
}
